package fr.rowlaxx.springkutils.math.data

import java.util.TreeMap

/**
 * An optimized version of BitSet and BitMap that stores numbers as ranges (segments).
 *
 * For example, if the instance contains numbers from 0 (inclusive) to 10 (inclusive),
 * it is represented internally as a single segment {0: 10}.
 * If it contains 0, 1, 4 and 5, it is represented as {0: 1, 4: 5}.
 *
 * This representation is particularly efficient when bits are clustered together.
 */
open class SegmentedBitSet internal constructor(
    internal val content: TreeMap<Long, Long>
) {
    /**
     * Creates an empty SegmentedBitSet.
     */
    constructor() : this(TreeMap<Long, Long>())

    companion object {
        /**
         * A static element representing an empty SegmentedBitSet.
         */
        @JvmField
        val EMPTY = SegmentedBitSet()
    }

    /**
     * Returns true if this bit set contains the specified number.
     */
    fun contains(number: Long): Boolean {
        val entry = content.floorEntry(number) ?: return false
        return number <= entry.value
    }

    /**
     * Returns true if this bit set contains all numbers in the specified range.
     */
    fun containsAll(range: LongRange): Boolean {
        if (range.isEmpty()) return true
        val entry = content.floorEntry(range.first) ?: return false
        return range.last <= entry.value
    }

    /**
     * Returns a new bit set that is the union of this bit set and the other bit set.
     */
    infix fun union(other: SegmentedBitSet): SegmentedBitSet {
        val result = copy()
        other.content.forEach { (start, end) -> result.addAll(start..end) }
        return result
    }

    /**
     * Returns a new bit set that is the intersection of this bit set and the other bit set.
     */
    infix fun intersect(other: SegmentedBitSet): SegmentedBitSet {
        val result = MutableSegmentedBitSet()
        content.forEach { (s1, e1) ->
            var current = s1
            while (current <= e1) {
                val entry = other.content.floorEntry(current)
                if (entry != null && current <= entry.value) {
                    val intersectStart = maxOf(current, entry.key)
                    val intersectEnd = minOf(e1, entry.value)
                    result.addAll(intersectStart..intersectEnd)
                    if (intersectEnd == Long.MAX_VALUE) break
                    current = intersectEnd + 1
                } else {
                    val nextEntry = other.content.ceilingEntry(current)
                    if (nextEntry == null || nextEntry.key > e1) break
                    current = nextEntry.key
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
        intersection.content.forEach { (start, end) -> result.removeAll(start..end) }
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
        val newContent = TreeMap<Long, Long>()
        content.forEach { (start, end) ->
            newContent[start + count] = end + count
        }
        return SegmentedBitSet(newContent)
    }

    /**
     * Returns a new bit set with all numbers shifted to the left by the specified count.
     */
    fun leftShifted(count: Long): SegmentedBitSet {
        if (count == 0L) return this
        if (count < 0) return rightShifted(-count)
        val newContent = TreeMap<Long, Long>()
        content.forEach { (start, end) ->
            newContent[start - count] = end - count
        }
        return SegmentedBitSet(newContent)
    }

    /**
     * Returns a new bit set containing only the numbers in the specified range that are present in this bit set.
     */
    fun subset(range: LongRange): SegmentedBitSet {
        if (range.isEmpty()) return SegmentedBitSet()
        val result = MutableSegmentedBitSet()
        val startEntry = content.floorEntry(range.first)
        val relevantEntries = if (startEntry != null && startEntry.value >= range.first) {
            content.subMap(startEntry.key, true, range.last, true)
        } else {
            content.subMap(range.first, true, range.last, true)
        }

        relevantEntries.forEach { (s, e) ->
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
    fun first(): Long = try { content.firstKey() } catch (_: NoSuchElementException) { throw NoSuchElementException() }

    /**
     * Returns the smallest number present in this bit set that is greater than or equal to [from].
     * @throws NoSuchElementException if no such number exists.
     */
    fun next(from: Long): Long {
        val entry = content.floorEntry(from)
        if (entry != null && from <= entry.value) return from
        return content.ceilingKey(from) ?: throw NoSuchElementException()
    }

    /**
     * Returns the largest number present in this bit set that is less than or equal to [from].
     * @throws NoSuchElementException if no such number exists.
     */
    fun previous(from: Long): Long {
        val entry = content.floorEntry(from)
        if (entry != null && from <= entry.value) return from
        val previousEntry = content.lowerEntry(from) ?: throw NoSuchElementException()
        return previousEntry.value
    }
    
    /**
     * Returns the largest number present in this bit set that is less than or equal to [from],
     * or null if no such number exists.
     */
    fun previousOrNull(from: Long): Long? {
        val entry = content.floorEntry(from)
        if (entry != null && from <= entry.value) return from
        return content.lowerEntry(from)?.value
    }
    
    /**
     * Returns the smallest number present in this bit set that is greater than or equal to [from],
     * or null if no such number exists.
     */
    fun nextOrNull(from: Long): Long? {
        val entry = content.floorEntry(from)
        if (entry != null && from <= entry.value) return from
        return content.ceilingKey(from)
    }
    
    /**
     * Returns the largest number NOT present in this bit set that is less than or equal to [from],
     * or null if no such number exists (e.g. if the bit set contains [Long.MIN_VALUE] and [from] is [Long.MIN_VALUE]).
     */
    fun previousAbsentOrNull(from: Long): Long? {
        val entry = content.floorEntry(from)
        if (entry == null || from > entry.value) return from
        if (entry.key == Long.MIN_VALUE) return null
        return entry.key - 1
    }
    
    /**
     * Returns the smallest number NOT present in this bit set that is greater than or equal to [from],
     * or null if no such number exists (e.g. if the bit set contains [Long.MAX_VALUE] and [from] is [Long.MAX_VALUE]).
     */
    fun nextAbsentOrNull(from: Long): Long? {
        val entry = content.floorEntry(from)
        if (entry == null || from > entry.value) return from
        if (entry.value == Long.MAX_VALUE) return null
        return entry.value + 1
    }
    
    /**
     * Returns true if there is at least one number present in this bit set that is greater than or equal to [from].
     */
    fun hasNext(from: Long): Boolean {
        val entry = content.floorEntry(from)
        if (entry != null && from <= entry.value) return true
        return content.ceilingKey(from) != null
    }

    /**
     * Returns true if there is at least one number present in this bit set that is less than or equal to [from].
     */
    fun hasPrevious(from: Long): Boolean {
        val entry = content.floorEntry(from)
        return entry != null
    }

    /**
     * Returns the smallest number NOT present in this bit set that is greater than or equal to [from].
     */
    fun nextAbsent(from: Long): Long {
        val entry = content.floorEntry(from)
        if (entry == null || from > entry.value) return from
        if (entry.value == Long.MAX_VALUE) throw NoSuchElementException("No more absent bits (reached Long.MAX_VALUE)")
        return entry.value + 1
    }

    /**
     * Returns the largest number NOT present in this bit set that is less than or equal to [from].
     */
    fun previousAbsent(from: Long): Long {
        val entry = content.floorEntry(from)
        if (entry == null || from > entry.value) return from
        if (entry.key == Long.MIN_VALUE) throw NoSuchElementException("No more absent bits (reached Long.MIN_VALUE)")
        return entry.key - 1
    }

    /**
     * Returns the last number present in this bit set.
     * @throws NoSuchElementException if the bit set is empty.
     */
    fun last(): Long = content.lastEntry()?.value ?: throw NoSuchElementException()

    fun lastOrNull(): Long? = content.lastEntry()?.value

    fun firstOrNull(): Long? = content.firstEntry()?.value

    /**
     * Returns the total number of bits set to true.
     */
    fun size(): Long {
        var total = 0L
        content.forEach { (start, end) -> 
            total += (end - start + 1)
        }
        return total
    }

    /**
     * Iterates over each range of present elements in this bit set.
     *
     * @param action a function that takes the start and end of each range (inclusive).
     */
    fun forEachRange(action: (LongRange) -> Unit) {
        content.forEach { (start, end) -> action(start..end) }
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
        return SegmentedBitSet(TreeMap(content))
    }

    /**
     * Returns a mutable copy of this bit set.
     */
    fun copy(): MutableSegmentedBitSet {
        val newContent = TreeMap<Long, Long>(content)
        return MutableSegmentedBitSet(newContent)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SegmentedBitSet) return false
        return content == other.content
    }

    override fun hashCode(): Int = content.hashCode()

    override fun toString(): String = content.toString()
}