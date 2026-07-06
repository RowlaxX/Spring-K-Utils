package fr.rowlaxx.springkutils.math.data

import fr.rowlaxx.springkutils.array.MutableLongLongEntangledArray
import java.util.TreeMap

/**
 * An optimized version of BitSet and BitMap that stores numbers as ranges (segments).
 *
 * For example, if the instance contains numbers from 0 (inclusive) to 10 (inclusive),
 * it is represented internally as a single segment {0: 10}.
 * If it contains 0, 1, 4 and 5, it is represented as {0: 1, 4: 5}.
 *
 * This representation is particularly efficient when bits are clustered together.
 *
 * The segments are held in a [MutableLongLongEntangledArray] mapping each segment's start to its
 * (inclusive) end. Compared to the previous `TreeMap<Long, Long>` backing this stores two primitive
 * `long`s per segment in parallel arrays — no boxed keys/values and no per-entry tree node — which is
 * far lighter on memory and faster to scan while preserving the same ordered-navigation semantics.
 */
open class SegmentedBitSet internal constructor(
    internal val content: MutableLongLongEntangledArray
) {
    /**
     * Creates an empty SegmentedBitSet.
     */
    constructor() : this(MutableLongLongEntangledArray())

    /**
     * Builds a bit set from a legacy `start -> end` [TreeMap]. Retained so callers that still hand a
     * [TreeMap] over keep compiling; the entries are copied into the [MutableLongLongEntangledArray]
     * backing store.
     */
    internal constructor(content: TreeMap<Long, Long>) : this(fromTreeMap(content))

    companion object {
        /**
         * A static element representing an empty SegmentedBitSet.
         */
        @JvmField
        val EMPTY = SegmentedBitSet()

        private fun fromTreeMap(map: TreeMap<Long, Long>): MutableLongLongEntangledArray {
            val content = MutableLongLongEntangledArray(if (map.isEmpty()) 1 else map.size)
            for ((start, end) in map) {
                content.put(start, end)
            }
            return content
        }
    }

    /**
     * Returns true if this bit set contains the specified number.
     */
    fun contains(number: Long): Boolean {
        val key = content.floorKey(number) ?: return false
        return number <= content.getOrDefault(key, Long.MIN_VALUE)
    }

    /**
     * Returns true if this bit set contains all numbers in the specified range.
     */
    fun containsAll(range: LongRange): Boolean {
        if (range.isEmpty()) return true
        var current = range.first
        while (current <= range.last) {
            val key = content.floorKey(current) ?: return false
            val end = content.getOrDefault(key, Long.MIN_VALUE)
            if (end < current) return false
            if (end >= range.last) return true
            current = end + 1
        }
        return true
    }

    /**
     * Returns true if this bit set contains any number in the specified range.
     */
    fun containsAny(range: LongRange): Boolean {
        if (range.isEmpty()) return false
        val floor = content.floorKey(range.first)
        if (floor != null && range.first <= content.getOrDefault(floor, Long.MIN_VALUE)) return true
        val ceiling = content.ceilingKey(range.first) ?: return false
        return ceiling <= range.last
    }

    /**
     * Returns a new bit set that is the union of this bit set and the other bit set.
     */
    infix fun union(other: SegmentedBitSet): SegmentedBitSet {
        val result = copy()
        other.content.forEach { start, end -> result.addAll(start..end) }
        return result
    }

    /**
     * Returns a new bit set that is the intersection of this bit set and the other bit set.
     */
    infix fun intersect(other: SegmentedBitSet): SegmentedBitSet {
        val result = MutableSegmentedBitSet()
        content.forEach { s1, e1 ->
            var current = s1
            while (current <= e1) {
                val key = other.content.floorKey(current)
                val end = if (key != null) other.content.getOrDefault(key, Long.MIN_VALUE) else Long.MIN_VALUE
                if (key != null && current <= end) {
                    val intersectStart = maxOf(current, key)
                    val intersectEnd = minOf(e1, end)
                    result.addAll(intersectStart..intersectEnd)
                    if (intersectEnd == Long.MAX_VALUE) break
                    current = intersectEnd + 1
                } else {
                    val nextKey = other.content.ceilingKey(current)
                    if (nextKey == null || nextKey > e1) break
                    current = nextKey
                }
            }
        }
        return result
    }

    /**
     * Returns a new bit set that is the XOR of this bit set and the other bit set.
     */
    infix fun xor(other: SegmentedBitSet): SegmentedBitSet {
        val result = this.union(other).copy()
        val intersection = this.intersect(other)
        intersection.content.forEach { start, end -> result.removeAll(start..end) }
        return result
    }

    /**
     * Alias for [intersect].
     */
    infix fun and(other: SegmentedBitSet): SegmentedBitSet = intersect(other)

    /**
     * Alias for [union].
     */
    infix fun or(other: SegmentedBitSet): SegmentedBitSet = union(other)

    /**
     * Flipped is not supported on the whole bitset because it would be infinite.
     * Use [subset] followed by a flip operation on a [MutableSegmentedBitSet] instead.
     */
    fun flipped(): SegmentedBitSet {
        throw UnsupportedOperationException("Flipping an infinite or semi-infinite BitSet is not supported without a range. Use subset(...).flipped() or similar if you need a range.")
    }

    /**
     * Returns a new bit set with all numbers shifted to the right by the specified count.
     */
    fun rightShifted(count: Long): SegmentedBitSet {
        if (count == 0L) return this
        if (count < 0) return leftShifted(-count)
        val newContent = MutableLongLongEntangledArray(if (content.isEmpty()) 1 else content.size)
        content.forEach { start, end ->
            newContent.put(start + count, end + count)
        }
        return SegmentedBitSet(newContent)
    }

    /**
     * Returns a new bit set with all numbers shifted to the left by the specified count.
     */
    fun leftShifted(count: Long): SegmentedBitSet {
        if (count == 0L) return this
        if (count < 0) return rightShifted(-count)
        val newContent = MutableLongLongEntangledArray(if (content.isEmpty()) 1 else content.size)
        content.forEach { start, end ->
            newContent.put(start - count, end - count)
        }
        return SegmentedBitSet(newContent)
    }

    /**
     * Returns a new bit set containing only the numbers in the specified range that are present in this bit set.
     */
    fun subset(range: LongRange): SegmentedBitSet {
        if (range.isEmpty()) return SegmentedBitSet()
        val result = MutableSegmentedBitSet()
        val floorKey = content.floorKey(range.first)
        val firstKey = if (floorKey != null && content.getOrDefault(floorKey, Long.MIN_VALUE) >= range.first) {
            floorKey
        } else {
            content.ceilingKey(range.first)
        }
        if (firstKey == null || firstKey > range.last) return result

        // The relevant segment straddling or at the lower boundary.
        val firstEnd = content.getOrDefault(firstKey, Long.MIN_VALUE)
        val firstIntersectStart = maxOf(range.first, firstKey)
        val firstIntersectEnd = minOf(range.last, firstEnd)
        if (firstIntersectStart <= firstIntersectEnd) {
            result.addAll(firstIntersectStart..firstIntersectEnd)
        }

        // Every subsequent segment whose start is in (firstKey, range.last].
        content.forEachInRange(firstKey, range.last) { s, e ->
            val intersectStart = maxOf(range.first, s)
            val intersectEnd = minOf(range.last, e)
            if (intersectStart <= intersectEnd) {
                result.addAll(intersectStart..intersectEnd)
            }
        }
        return result
    }

    /**
     * Returns the first number present in this bit set.
     * @throws NoSuchElementException if the bit set is empty.
     */
    fun first(): Long = if (content.isEmpty()) throw NoSuchElementException() else content.firstKey

    /**
     * Returns the smallest number present in this bit set that is greater than or equal to [from].
     * @throws NoSuchElementException if no such number exists.
     */
    fun next(from: Long): Long {
        val key = content.floorKey(from)
        if (key != null && from <= content.getOrDefault(key, Long.MIN_VALUE)) return from
        return content.ceilingKey(from) ?: throw NoSuchElementException()
    }

    /**
     * Returns the largest number present in this bit set that is less than or equal to [from].
     * @throws NoSuchElementException if no such number exists.
     */
    fun previous(from: Long): Long {
        val key = content.floorKey(from)
        if (key != null && from <= content.getOrDefault(key, Long.MIN_VALUE)) return from
        return content.lower(from) ?: throw NoSuchElementException()
    }

    /**
     * Returns the largest number present in this bit set that is less than or equal to [from],
     * or null if no such number exists.
     */
    fun previousOrNull(from: Long): Long? {
        val key = content.floorKey(from)
        if (key != null && from <= content.getOrDefault(key, Long.MIN_VALUE)) return from
        return content.lower(from)
    }

    /**
     * Returns the smallest number present in this bit set that is greater than or equal to [from],
     * or null if no such number exists.
     */
    fun nextOrNull(from: Long): Long? {
        val key = content.floorKey(from)
        if (key != null && from <= content.getOrDefault(key, Long.MIN_VALUE)) return from
        return content.ceilingKey(from)
    }

    /**
     * Returns the largest number NOT present in this bit set that is less than or equal to [from],
     * or null if no such number exists (e.g. if the bit set contains [Long.MIN_VALUE] and [from] is [Long.MIN_VALUE]).
     */
    fun previousAbsentOrNull(from: Long): Long? {
        val key = content.floorKey(from)
        if (key == null || from > content.getOrDefault(key, Long.MIN_VALUE)) return from
        if (key == Long.MIN_VALUE) return null
        return key - 1
    }

    /**
     * Returns the smallest number NOT present in this bit set that is greater than or equal to [from],
     * or null if no such number exists (e.g. if the bit set contains [Long.MAX_VALUE] and [from] is [Long.MAX_VALUE]).
     */
    fun nextAbsentOrNull(from: Long): Long? {
        val key = content.floorKey(from) ?: return from
        val end = content.getOrDefault(key, Long.MIN_VALUE)
        if (from > end) return from
        if (end == Long.MAX_VALUE) return null
        return end + 1
    }

    /**
     * Returns true if there is at least one number present in this bit set that is greater than or equal to [from].
     */
    fun hasNext(from: Long): Boolean {
        val key = content.floorKey(from)
        if (key != null && from <= content.getOrDefault(key, Long.MIN_VALUE)) return true
        return content.ceilingKey(from) != null
    }

    /**
     * Returns true if there is at least one number present in this bit set that is less than or equal to [from].
     */
    fun hasPrevious(from: Long): Boolean {
        return content.floorKey(from) != null
    }

    /**
     * Returns the smallest number NOT present in this bit set that is greater than or equal to [from].
     */
    fun nextAbsent(from: Long): Long {
        val key = content.floorKey(from) ?: return from
        val end = content.getOrDefault(key, Long.MIN_VALUE)
        if (from > end) return from
        if (end == Long.MAX_VALUE) throw NoSuchElementException("No more absent bits (reached Long.MAX_VALUE)")
        return end + 1
    }

    /**
     * Returns the largest number NOT present in this bit set that is less than or equal to [from].
     */
    fun previousAbsent(from: Long): Long {
        val key = content.floorKey(from)
        if (key == null || from > content.getOrDefault(key, Long.MIN_VALUE)) return from
        if (key == Long.MIN_VALUE) throw NoSuchElementException("No more absent bits (reached Long.MIN_VALUE)")
        return key - 1
    }

    /**
     * Returns the last number present in this bit set.
     * @throws NoSuchElementException if the bit set is empty.
     */
    fun last(): Long = content.lastOrNull ?: throw NoSuchElementException()

    fun lastOrNull(): Long? = content.lastOrNull

    fun firstOrNull(): Long? = content.firstOrNull

    /**
     * Returns the total number of bits set to true.
     */
    fun size(): Long {
        var total = 0L
        content.forEach { start, end ->
            total += (end - start + 1)
        }
        return total
    }

    /**
     * Returns true if no bit is set to true in this bit set.
     */
    fun isEmpty(): Boolean = content.isEmpty()

    /**
     * Returns true if at least one bit is set to true in this bit set.
     */
    fun isNotEmpty(): Boolean = content.isNotEmpty()

    /**
     * Returns every bit set to true, in ascending order, as a [List].
     *
     * Only supported when the number of set bits fits in an [Int]: the result is a materialised list,
     * so a set whose [size] exceeds [Int.MAX_VALUE] cannot be represented.
     *
     * @throws IllegalStateException if [size] is greater than [Int.MAX_VALUE].
     */
    fun toList(): List<Long> {
        if (content.isEmpty()) {
            return emptyList()
        }

        val size = size()
        if (size > Int.MAX_VALUE) {
            throw IllegalStateException("Cannot convert a SegmentedBitSet with $size elements to a list: it exceeds Int.MAX_VALUE")
        }

        val result = ArrayList<Long>(size.toInt())
        content.forEach { start, end ->
            var current = start
            while (current <= end) {
                result.add(current)
                if (current == Long.MAX_VALUE) break
                current++
            }
        }
        return result
    }

    /**
     * Iterates over each range of present elements in this bit set.
     *
     * @param action a function that takes the start and end of each range (inclusive).
     */
    fun forEachRange(action: (LongRange) -> Unit) {
        content.forEach { start, end -> action(start..end) }
    }

    /**
     * Iterates over each range of absent elements in this bit set within the specified range.
     *
     * @param range the range to search for absent elements.
     * @param action a function that takes the start and end of each absent range (inclusive).
     */
    fun forEachAbsentRange(range: LongRange = Long.MIN_VALUE..Long.MAX_VALUE, action: (LongRange) -> Unit) {
        if (range.isEmpty()) {
            return
        }

        var current = range.first

        while (current <= range.last) {
            val absentStart = nextAbsentOrNull(current) ?: break

            if (absentStart > range.last) {
                break
            }

            val nextPresent = nextOrNull(absentStart)
            val absentEnd = if (nextPresent == null || nextPresent > range.last) {
                range.last
            } else {
                nextPresent - 1
            }

            action(absentStart..absentEnd)

            if (absentEnd == Long.MAX_VALUE || absentEnd >= range.last) {
                break
            }

            current = absentEnd + 1
        }
    }

    /**
     * Returns an immutable view of this bit set.
     */
    fun immutableView(): SegmentedBitSet {
        if (this !is MutableSegmentedBitSet) return this
        return SegmentedBitSet(content)
    }

    /**
     * Returns an immutable copy of this bit set.
     */
    fun immutableCopy(): SegmentedBitSet {
        return SegmentedBitSet(content.copy())
    }

    /**
     * Returns a mutable copy of this bit set.
     */
    fun copy(): MutableSegmentedBitSet {
        return MutableSegmentedBitSet(content.copy())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentedBitSet) return false
        return content == other.content
    }

    override fun hashCode(): Int = content.hashCode()

    override fun toString(): String = content.toString()
}
