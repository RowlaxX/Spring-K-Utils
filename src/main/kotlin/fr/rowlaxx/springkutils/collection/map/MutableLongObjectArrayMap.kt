package fr.rowlaxx.springkutils.collection.map

import fr.rowlaxx.springkutils.collection.map.MutableLongObjectArrayMap.Companion.GROW_FACTOR
import fr.rowlaxx.springkutils.collection.map.MutableLongObjectArrayMap.Companion.MIN_SHRINK_CAPACITY
import fr.rowlaxx.springkutils.collection.map.MutableLongObjectArrayMap.Companion.SHRINK_FACTOR
import java.util.*

/**
 * A mutable, sorted `long -> V` map backed by two parallel arrays — a [LongArray] of keys and an
 * [Array] of values — mirroring the twin-array, `[start, end)` window design of
 * [fr.rowlaxx.marketdata.common.vector.IntDoubleEntangledArray]. It exists to replace `HashMap<Long, V>` for the candle factory's
 * builder store, where keys are `timeId`s: large, contiguous, monotonically increasing integers of
 * which only a bounded recent window is ever alive.
 *
 * Versus `HashMap<Long, V>` this carries no per-entry `Node` object and never boxes the `long` key,
 * so reads/writes allocate nothing on the steady-state path and stay cache-friendly. Keys are held
 * strictly ascending in `[start, end)`, so [get]/[containsKey] are a binary search, appending a new
 * highest key is O(1) amortized, and evicting the lowest key just advances [start]. An out-of-order
 * insert or a mid-array removal shifts with `System.arraycopy` (O(size), and size is tiny here).
 *
 * Not thread-safe: every caller in the candle factory already holds the per-market lock.
 */
class MutableLongObjectArrayMap<V>(initialCapacity: Int = 64) {

    private var keys: LongArray = LongArray(if (initialCapacity < 1) 1 else initialCapacity)
    private var values: Array<Any?> = arrayOfNulls(if (initialCapacity < 1) 1 else initialCapacity)
    private var start = 0
    private var end = 0

    val size get() = end - start
    fun isEmpty(): Boolean = end == start
    fun isNotEmpty(): Boolean = end != start

    val firstKey: Long get() = if (size == 0) throw NoSuchElementException() else keys[start]
    val lastKey: Long get() = if (size == 0) throw NoSuchElementException() else keys[end - 1]

    @Suppress("UNCHECKED_CAST")
    val first: V get() = if (size == 0) throw NoSuchElementException() else values[start] as V

    @Suppress("UNCHECKED_CAST")
    val last: V get() = if (size == 0)  throw NoSuchElementException() else values[end - 1] as V

    @Suppress("UNCHECKED_CAST")
    val lastOrNull: V? get() = if (size == 0) null else values[end - 1] as V

    @Suppress("UNCHECKED_CAST")
    val firstOrNull: V? get() = if (size == 0) null else values[start] as V

    private fun search(key: Long): Int {
        var lo = start
        var hi = end - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val mk = keys[mid]
            when {
                mk < key -> lo = mid + 1
                mk > key -> hi = mid - 1
                else -> return mid
            }
        }
        return -(lo + 1)
    }

    operator fun get(key: Long): V? {
        if (end > start) {
            if (key == keys[end - 1]) { @Suppress("UNCHECKED_CAST") return values[end - 1] as V }
            if (key == keys[start]) { @Suppress("UNCHECKED_CAST") return values[start] as V }
        }
        val i = search(key)
        @Suppress("UNCHECKED_CAST")
        return if (i >= 0) values[i] as V else null
    }

    fun containsKey(key: Long): Boolean {
        if (end > start && (key == keys[end - 1] || key == keys[start])) return true
        return search(key) >= 0
    }

    fun put(key: Long, value: V): V? {
        val i = search(key)
        if (i >= 0) {
            @Suppress("UNCHECKED_CAST")
            val old = values[i] as V
            values[i] = value
            return old
        }
        insertAbsent(key, value)
        return null
    }

    fun getOrPut(key: Long, defaultValue: (Long) -> V): V {
        val i = search(key)
        if (i >= 0) {
            @Suppress("UNCHECKED_CAST")
            return values[i] as V
        }
        val value = defaultValue(key)
        insertAbsent(key, value)
        return value
    }

    fun remove(key: Long): V? {
        if (end > start) {
            if (key == keys[start]) {
                @Suppress("UNCHECKED_CAST")
                val old = values[start] as V
                values[start] = null
                start++
                if (start == end) { start = 0; end = 0 }
                maybeShrink()
                return old
            }
            if (key == keys[end - 1]) {
                @Suppress("UNCHECKED_CAST")
                val old = values[end - 1] as V
                end--
                values[end] = null
                if (start == end) { start = 0; end = 0 }
                maybeShrink()
                return old
            }
        }
        val i = search(key)
        if (i < 0) return null
        @Suppress("UNCHECKED_CAST")
        val old = values[i] as V
        removeAt(i)
        maybeShrink()
        return old
    }

    fun forEach(action: (Long, V) -> Unit) {
        for (i in start until end) {
            @Suppress("UNCHECKED_CAST")
            action(keys[i], values[i] as V)
        }
    }

    /** First index in `[start, end)` whose key is `>= key`, or [end] if none (keys are ascending). */
    private fun lowerBound(key: Long): Int {
        var lo = start
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index in `[start, end)` whose key is `> key`, or [end] if none (keys are ascending). */
    private fun upperBound(key: Long): Int {
        var lo = start
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] <= key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * The value of the greatest key strictly less than [key], or `null` when none exists (keys are
     * ascending). O(log n): a single binary search for the boundary.
     */
    fun lower(key: Long): V? {
        val i = lowerBound(key) - 1
        if (i < start) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return values[i] as V
    }

    /**
     * Number of entries whose key is after [key] — strictly `> key`, or `>= key` when [inclusive].
     * O(log n): only the boundary is binary-searched, no iteration.
     */
    fun countAfter(key: Long, inclusive: Boolean): Int {
        val from = if (inclusive) lowerBound(key) else upperBound(key)
        return end - from
    }

    /**
     * Number of entries whose key is before [key] — strictly `< key`, or `<= key` when [inclusive].
     * O(log n): only the boundary is binary-searched, no iteration.
     */
    fun countBefore(key: Long, inclusive: Boolean): Int {
        val to = if (inclusive) upperBound(key) else lowerBound(key)
        return to - start
    }

    /**
     * Visits every entry whose key is after [key] — strictly `> key`, or `>= key` when [inclusive] — in
     * ascending key order. Binary-searches the boundary, then walks only the matching suffix.
     */
    fun forEachAfter(key: Long, inclusive: Boolean, action: (Long, V) -> Unit) {
        val from = if (inclusive) lowerBound(key) else upperBound(key)
        for (i in from until end) {
            @Suppress("UNCHECKED_CAST")
            action(keys[i], values[i] as V)
        }
    }

    /**
     * Visits every entry whose key is before [key] — strictly `< key`, or `<= key` when [inclusive] — in
     * ascending key order. Binary-searches the boundary, then walks only the matching prefix.
     */
    fun forEachBefore(key: Long, inclusive: Boolean, action: (Long, V) -> Unit) {
        val to = if (inclusive) upperBound(key) else lowerBound(key)
        for (i in start until to) {
            @Suppress("UNCHECKED_CAST")
            action(keys[i], values[i] as V)
        }
    }

    /**
     * The smallest key greater than or equal to [key], or `null` when none exists (keys are
     * ascending). O(log n): a single binary search for the boundary.
     */
    fun ceilingKey(key: Long): Long? {
        val i = lowerBound(key)
        return if (i < end) keys[i] else null
    }

    /**
     * Visits every entry whose key is in `(fromExclusive, toInclusive]`, in ascending key order —
     * mirroring `TreeMap.subMap(fromExclusive, false, toInclusive, true)`. Binary-searches the lower
     * boundary, then walks until [toInclusive] is passed.
     */
    fun forEachInRange(fromExclusive: Long, toInclusive: Long, action: (Long, V) -> Unit) {
        var i = upperBound(fromExclusive)
        while (i < end) {
            val k = keys[i]
            if (k > toInclusive) break
            @Suppress("UNCHECKED_CAST")
            action(k, values[i] as V)
            i++
        }
    }

    fun removeIf(predicate: (Long, V) -> Boolean) {
        var w = start
        for (r in start until end) {
            val k = keys[r]
            @Suppress("UNCHECKED_CAST")
            val v = values[r] as V
            if (!predicate(k, v)) {
                if (w != r) {
                    keys[w] = k
                    values[w] = v
                }
                w++
            }
        }
        Arrays.fill(values, w, end, null)
        end = w
        if (start == end) { start = 0; end = 0 }
        maybeShrink()
    }

    /** Removes every entry, nulling the value slots so they don't pin their objects. */
    fun clear() {
        Arrays.fill(values, start, end, null)
        start = 0
        end = 0
    }

    /**
     * Removes every entry whose key is before [at] — strictly `< at`, or `<= at` when [inclusive].
     * O(log n) to find the boundary, then O(1) to advance [start] and null the freed value slots.
     */
    fun removeBefore(at: Long, inclusive: Boolean) {
        if (end == start) return
        val boundary = if (inclusive) upperBound(at) else lowerBound(at)
        if (boundary <= start) return
        Arrays.fill(values, start, boundary, null)
        start = boundary
        if (start == end) { start = 0; end = 0 }
        maybeShrink()
    }

    /**
     * Removes every entry whose key is after [at] — strictly `> at`, or `>= at` when [inclusive].
     * O(log n) to find the boundary, then O(1) to shrink [end] and null the freed value slots.
     */
    fun removeAfter(at: Long, inclusive: Boolean) {
        if (end == start) return
        val boundary = if (inclusive) lowerBound(at) else upperBound(at)
        if (boundary >= end) return
        Arrays.fill(values, boundary, end, null)
        end = boundary
        if (start == end) { start = 0; end = 0 }
        maybeShrink()
    }

    /**
     * Removes every entry whose key falls inside [range], both bounds inclusive. Binary-searches the
     * two boundaries, then closes the hole. A range that touches [start] just advances it and a range
     * that touches [end] just shrinks it, so neither copies; only a hole strictly inside the live
     * window pays one `System.arraycopy` of the surviving tail.
     */
    fun removeRange(range: LongRange) {
        if (end == start || range.isEmpty()) return
        val from = lowerBound(range.first)
        val to = upperBound(range.last)
        if (from >= to) return

        if (from == start) {
            Arrays.fill(values, start, to, null)
            start = to
            if (start == end) { start = 0; end = 0 }
            maybeShrink()
            return
        }
        if (to == end) {
            Arrays.fill(values, from, end, null)
            end = from
            maybeShrink()
            return
        }

        val kept = end - to
        System.arraycopy(keys, to, keys, from, kept)
        System.arraycopy(values, to, values, from, kept)
        Arrays.fill(values, from + kept, end, null)
        end = from + kept
        maybeShrink()
    }

    /**
     * Keeps only the [n] lowest keys, dropping the rest. O(1) window move plus nulling of the dropped
     * value slots — no shifting. `n <= 0` empties the map, `n >= size` is a no-op.
     */
    fun retainFirst(n: Int) {
        if (n >= size) return
        retainWindow(start, if (n <= 0) start else start + n)
    }

    /**
     * Keeps only the [n] highest keys, dropping the rest. O(1) window move plus nulling of the
     * dropped value slots — no shifting. `n <= 0` empties the map, `n >= size` is a no-op.
     */
    fun retainLast(n: Int) {
        if (n >= size) return
        retainWindow(if (n <= 0) end else end - n, end)
    }

    /**
     * Keeps only the entries whose 0-based position lies inside [range], dropping everything before
     * and after. Positions are clamped to the live window, so a fully out-of-bounds or empty range
     * clears the map and an over-wide range keeps everything. A range that touches an end is a
     * shortcut to [retainFirst]/[retainLast]; only a range with entries on both sides nulls two tails.
     */
    fun retain(range: IntRange) {
        val last = range.last.coerceAtMost(size - 1)
        if (range.first > last) {
            retainWindow(start, start)
            return
        }
        if (range.first <= 0) {
            retainFirst(last + 1)
            return
        }
        if (last == size - 1) {
            retainLast(size - range.first)
            return
        }
        retainWindow(start + range.first, start + last + 1)
    }

    /**
     * Narrows the live window to the absolute slice `[newStart, newEnd)`, nulling every value slot it
     * drops. Bounds are clamped to the current `[start, end)`; an empty result resets the map to
     * empty. O(dropped) with no array copy, followed by the usual post-removal shrink check.
     */
    private fun retainWindow(newStart: Int, newEnd: Int) {
        val from = newStart.coerceIn(start, end)
        val to = newEnd.coerceIn(start, end)
        if (from >= to) {
            Arrays.fill(values, start, end, null)
            start = 0
            end = 0
            maybeShrink()
            return
        }
        Arrays.fill(values, start, from, null)
        Arrays.fill(values, to, end, null)
        start = from
        end = to
        maybeShrink()
    }

    private fun insertAbsent(key: Long, value: V) {
        ensureRoom()
        val ip = -(search(key) + 1) // insertion point in [start, end]
        if (ip < end) {
            System.arraycopy(keys, ip, keys, ip + 1, end - ip)
            System.arraycopy(values, ip, values, ip + 1, end - ip)
        }
        keys[ip] = key
        values[ip] = value
        end++
    }

    private fun ensureRoom() {
        if (end < keys.size) return

        if (start > 0) {
            val n = size
            System.arraycopy(keys, start, keys, 0, n)
            System.arraycopy(values, start, values, 0, n)
            Arrays.fill(values, n, keys.size, null)
            start = 0
            end = n
            if (end < keys.size) return
        }

        val newCapacity = maxOf(keys.size + 1, (keys.size * GROW_FACTOR).toInt())
        keys = keys.copyOf(newCapacity)
        values = values.copyOf(newCapacity)
    }

    private fun removeAt(i: Int) {
        when (i) {
            start -> {
                values[start] = null
                start++
            }
            end - 1 -> {
                end--
                values[end] = null
            }
            else -> {
                System.arraycopy(keys, i + 1, keys, i, end - i - 1)
                System.arraycopy(values, i + 1, values, i, end - i - 1)
                end--
                values[end] = null
            }
        }
        if (start == end) { start = 0; end = 0 }
    }

    /**
     * Called after a removal to release capacity retained by a transient size spike. Shrinking is
     * gated by [SHRINK_FACTOR] to leave a hysteresis gap against growth: growth multiplies the arrays
     * by [GROW_FACTOR], so a freshly grown map sits at `1 / GROW_FACTOR` (= 66.7 %) full. Because
     * `SHRINK_FACTOR > GROW_FACTOR`, that is still above the shrink low-water mark
     * (`1 / SHRINK_FACTOR` = 50 %), so a single add or remove right after any resize can never
     * trigger the opposite resize — only a sustained move of at least a third does.
     *
     * When the mark is crossed the arrays are reallocated so the live size fills `1 / GROW_FACTOR`
     * (i.e. [GROW_FACTOR] times the live size, = 66.7 %) of the smaller arrays, which is itself above
     * the low-water mark, so the shrink cannot immediately be undone by a grow either. The old,
     * larger value array is dropped whole, releasing every reference in its now-unused slots. A no-op
     * when usage is still healthy or when shrinking wouldn't actually reduce the capacity.
     *
     * The floor is on the *capacity*, not the live size: arrays at or below [MIN_SHRINK_CAPACITY] are
     * already small enough that reallocating them is not worth the churn, and no shrink ever takes a
     * map below it. Keying the floor on the live size instead would exempt exactly the maps that have
     * collapsed the furthest — a long-lived map oscillating between a spike and a handful of entries
     * would pin its peak capacity forever.
     */
    private fun maybeShrink() {
        val capacity = keys.size
        val n = end - start
        if (capacity <= MIN_SHRINK_CAPACITY) return
        if (n.toLong() * SHRINK_FACTOR >= capacity.toLong()) return

        val newCapacity = maxOf(MIN_SHRINK_CAPACITY, (n * GROW_FACTOR).toInt())
        if (newCapacity >= capacity) return

        val newKeys = LongArray(newCapacity)
        val newValues = arrayOfNulls<Any?>(newCapacity)
        System.arraycopy(keys, start, newKeys, 0, n)
        System.arraycopy(values, start, newValues, 0, n)
        keys = newKeys
        values = newValues
        start = 0
        end = n
    }

    private companion object {
        private const val MIN_SHRINK_CAPACITY = 16

        /** Capacity multiplier applied whenever the arrays fill up. */
        private const val GROW_FACTOR = 1.5

        /**
         * Shrink only once the live size fits [SHRINK_FACTOR] times into the arrays. Must be strictly
         * greater than [GROW_FACTOR] so the post-grow load (`1 / GROW_FACTOR`) stays above the shrink
         * low-water mark (`1 / SHRINK_FACTOR`), which is what prevents a resize from being undone by
         * the next add/remove.
         */
        private const val SHRINK_FACTOR = 2
    }
}
