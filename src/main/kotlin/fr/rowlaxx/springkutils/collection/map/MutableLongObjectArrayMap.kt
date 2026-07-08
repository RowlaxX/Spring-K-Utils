package fr.rowlaxx.springkutils.collection.map

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
    val isEmpty get() = end == start
    val isNotEmpty get() = end != start

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
                return old
            }
            if (key == keys[end - 1]) {
                @Suppress("UNCHECKED_CAST")
                val old = values[end - 1] as V
                end--
                values[end] = null
                if (start == end) { start = 0; end = 0 }
                return old
            }
        }
        val i = search(key)
        if (i < 0) return null
        @Suppress("UNCHECKED_CAST")
        val old = values[i] as V
        removeAt(i)
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
    }

    /** Removes every entry, nulling the value slots so they don't pin their objects. */
    fun clear() {
        Arrays.fill(values, start, end, null)
        start = 0
        end = 0
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

        val newCapacity = keys.size * 2
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
}
