package fr.rowlaxx.springkutils.collection.map

import java.nio.ByteBuffer

/**
 * A mutable, sorted `long -> long` map backed by two parallel [LongArray]s — one of keys and one of
 * values — mirroring the twin-array, `[start, end)` window design of [MutableLongObjectArrayMap].
 * It is the all-primitive twin of that class: where [MutableLongObjectArrayMap] stores object
 * values (like [fr.rowlaxx.marketdata.common.vector.IntIntEntangledArray] is the all-primitive twin of `IntDoubleEntangledArray`), this
 * one stores primitive `long` values, so neither the key nor the value is ever boxed.
 *
 * Versus `HashMap<Long, Long>` this carries no per-entry `Node` object and boxes neither the key nor
 * the value, so reads/writes allocate nothing on the steady-state path and stay cache-friendly. Keys
 * are held strictly ascending in `[start, end)`, so [get]/[containsKey] are a binary search,
 * appending a new highest key is O(1) amortized, and evicting the lowest key just advances [start].
 * An out-of-order insert or a mid-array removal shifts with `System.arraycopy` (O(size), and size is
 * tiny here).
 *
 * Because the value store is a primitive [LongArray], a present key is never distinguishable by its
 * slot contents — absence is decided purely by the `[start, end)` window and the binary search. The
 * nullable [get] returns `null` for a missing key; the primitive [getOrDefault] avoids even boxing
 * the result. Freed slots need no clearing since a `long` pins no object.
 *
 * Not thread-safe: every caller is expected to already hold the relevant per-market lock.
 */
class MutableLongLongArrayMap(initialCapacity: Int = 64) {

    private var keys: LongArray = LongArray(if (initialCapacity < 1) 1 else initialCapacity)
    private var values: LongArray = LongArray(if (initialCapacity < 1) 1 else initialCapacity)
    private var start = 0
    private var end = 0

    val size get() = end - start
    fun isEmpty(): Boolean = end == start
    fun isNotEmpty(): Boolean = end != start

    val firstKey: Long get() = if (size == 0) throw NoSuchElementException() else keys[start]
    val lastKey: Long get() = if (size == 0) throw NoSuchElementException() else keys[end - 1]

    val first: Long get() = if (size == 0) throw NoSuchElementException() else values[start]
    val last: Long get() = if (size == 0) throw NoSuchElementException() else values[end - 1]

    val lastOrNull: Long? get() = if (size == 0) null else values[end - 1]
    val firstOrNull: Long? get() = if (size == 0) null else values[start]

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

    /** The value mapped to [key], or `null` when absent. Boxes the result; prefer [getOrDefault] on the hot path. */
    operator fun get(key: Long): Long? {
        if (end > start) {
            if (key == keys[end - 1]) return values[end - 1]
            if (key == keys[start]) return values[start]
        }
        val i = search(key)
        return if (i >= 0) values[i] else null
    }

    /** The value mapped to [key], or [default] when absent. Allocation-free — nothing is boxed. */
    fun getOrDefault(key: Long, default: Long): Long {
        if (end > start) {
            if (key == keys[end - 1]) return values[end - 1]
            if (key == keys[start]) return values[start]
        }
        val i = search(key)
        return if (i >= 0) values[i] else default
    }

    fun containsKey(key: Long): Boolean {
        if (end > start && (key == keys[end - 1] || key == keys[start])) return true
        return search(key) >= 0
    }

    fun put(key: Long, value: Long): Long? {
        val i = search(key)
        if (i >= 0) {
            val old = values[i]
            values[i] = value
            return old
        }
        insertAbsent(key, value)
        return null
    }

    fun getOrPut(key: Long, defaultValue: (Long) -> Long): Long {
        val i = search(key)
        if (i >= 0) {
            return values[i]
        }
        val value = defaultValue(key)
        insertAbsent(key, value)
        return value
    }

    fun remove(key: Long): Long? {
        if (end > start) {
            if (key == keys[start]) {
                val old = values[start]
                start++
                if (start == end) { start = 0; end = 0 }
                maybeShrink()
                return old
            }
            if (key == keys[end - 1]) {
                val old = values[end - 1]
                end--
                if (start == end) { start = 0; end = 0 }
                maybeShrink()
                return old
            }
        }
        val i = search(key)
        if (i < 0) return null
        val old = values[i]
        removeAt(i)
        maybeShrink()
        return old
    }

    fun forEach(action: (Long, Long) -> Unit) {
        for (i in start until end) {
            action(keys[i], values[i])
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
    fun lower(key: Long): Long? {
        val i = lowerBound(key) - 1
        if (i < start) {
            return null
        }
        return values[i]
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
    fun forEachAfter(key: Long, inclusive: Boolean, action: (Long, Long) -> Unit) {
        val from = if (inclusive) lowerBound(key) else upperBound(key)
        for (i in from until end) {
            action(keys[i], values[i])
        }
    }

    /**
     * Visits every entry whose key is before [key] — strictly `< key`, or `<= key` when [inclusive] — in
     * ascending key order. Binary-searches the boundary, then walks only the matching prefix.
     */
    fun forEachBefore(key: Long, inclusive: Boolean, action: (Long, Long) -> Unit) {
        val to = if (inclusive) upperBound(key) else lowerBound(key)
        for (i in start until to) {
            action(keys[i], values[i])
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
     * The greatest key less than or equal to [key], or `null` when none exists (keys are ascending).
     * The symmetric counterpart of [ceilingKey]. O(log n): a single binary search for the boundary.
     */
    fun floorKey(key: Long): Long? {
        val i = upperBound(key) - 1
        return if (i < start) null else keys[i]
    }

    /**
     * Visits every entry whose key is in `(fromExclusive, toInclusive]`, in ascending key order —
     * mirroring `TreeMap.subMap(fromExclusive, false, toInclusive, true)`. Binary-searches the lower
     * boundary, then walks until [toInclusive] is passed.
     */
    fun forEachInRange(fromExclusive: Long, toInclusive: Long, action: (Long, Long) -> Unit) {
        var i = upperBound(fromExclusive)
        while (i < end) {
            val k = keys[i]
            if (k > toInclusive) break
            action(k, values[i])
            i++
        }
    }

    fun removeIf(predicate: (Long, Long) -> Boolean) {
        var w = start
        for (r in start until end) {
            val k = keys[r]
            val v = values[r]
            if (!predicate(k, v)) {
                if (w != r) {
                    keys[w] = k
                    values[w] = v
                }
                w++
            }
        }
        end = w
        if (start == end) { start = 0; end = 0 }
        maybeShrink()
    }

    /** Removes every entry. A primitive value pins nothing, so no slot needs clearing. */
    fun clear() {
        start = 0
        end = 0
    }

    /** A deep, compacted copy: a new instance whose entries are independent of this one. */
    fun copy(): MutableLongLongArrayMap {
        val n = size
        val c = MutableLongLongArrayMap(if (n < 1) 1 else n)
        System.arraycopy(keys, start, c.keys, 0, n)
        System.arraycopy(values, start, c.values, 0, n)
        c.end = n
        return c
    }

    /**
     * Serializes to `Int count` (4 bytes) followed by [count] ascending `(Long key, Long value)`
     * pairs (16 bytes each). The inverse of [deserialize]; an empty array is just the four zero bytes.
     */
    fun serialize(): ByteArray {
        val n = size
        val buffer = ByteBuffer.allocate(4 + n * 16)
        buffer.putInt(n)
        for (i in start until end) {
            buffer.putLong(keys[i])
            buffer.putLong(values[i])
        }
        return buffer.array()
    }

    /** Appends a key that is strictly greater than every existing key. Skips the ordered insert. */
    private fun appendAscending(key: Long, value: Long) {
        ensureRoom()
        keys[end] = key
        values[end] = value
        end++
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MutableLongLongArrayMap) return false
        if (size != other.size) return false
        var i = start
        var j = other.start
        while (i < end) {
            if (keys[i] != other.keys[j] || values[i] != other.values[j]) return false
            i++
            j++
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 1
        for (i in start until end) {
            h = 31 * h + keys[i].hashCode()
            h = 31 * h + values[i].hashCode()
        }
        return h
    }

    override fun toString(): String {
        if (isEmpty()) return "{}"
        val sb = StringBuilder("{")
        for (i in start until end) {
            if (i > start) sb.append(", ")
            sb.append(keys[i]).append('=').append(values[i])
        }
        return sb.append('}').toString()
    }

    private fun insertAbsent(key: Long, value: Long) {
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
            start -> start++
            end - 1 -> end--
            else -> {
                System.arraycopy(keys, i + 1, keys, i, end - i - 1)
                System.arraycopy(values, i + 1, values, i, end - i - 1)
                end--
            }
        }
        if (start == end) { start = 0; end = 0 }
    }

    /**
     * Called after a removal to release capacity retained by a transient size spike. Once the live
     * size drops below [SHRINK_THRESHOLD_PERCENT]% of the backing arrays, they are reallocated so the
     * new size fills ~[SHRINK_TARGET_PERCENT]% of the smaller arrays (leaving a little headroom before
     * the next grow). A no-op when usage is still healthy or when shrinking wouldn't actually reduce
     * the capacity.
     */
    private fun maybeShrink() {
        val capacity = keys.size
        val n = end - start
        if (n < MIN_SHRINK_SIZE) return
        if (n.toLong() * 100 >= capacity.toLong() * SHRINK_THRESHOLD_PERCENT) return

        val newCapacity = maxOf(1, ((n.toLong() * 100 + (SHRINK_TARGET_PERCENT - 1)) / SHRINK_TARGET_PERCENT).toInt())
        if (newCapacity >= capacity) return

        val newKeys = LongArray(newCapacity)
        val newValues = LongArray(newCapacity)
        System.arraycopy(keys, start, newKeys, 0, n)
        System.arraycopy(values, start, newValues, 0, n)
        keys = newKeys
        values = newValues
        start = 0
        end = n
    }

    companion object {
        private const val MIN_SHRINK_SIZE = 16
        private const val SHRINK_THRESHOLD_PERCENT = 70
        private const val SHRINK_TARGET_PERCENT = 85

        /**
         * Rebuilds an array from the byte form produced by [serialize]. The pairs are trusted to be in
         * ascending key order (as [serialize] always emits them), so they are bulk-appended without a
         * per-entry ordered insert. A truncated or malformed input raises [IllegalArgumentException].
         */
        fun deserialize(bytes: ByteArray): MutableLongLongArrayMap {
            val buffer = ByteBuffer.wrap(bytes)
            val count = try {
                buffer.getInt()
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid byte array: cannot read size", e)
            }

            val result = MutableLongLongArrayMap(if (count < 1) 1 else count)
            try {
                repeat(count) {
                    val key = buffer.getLong()
                    val value = buffer.getLong()
                    result.appendAscending(key, value)
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid byte array: cannot read entries", e)
            }
            return result
        }
    }
}
