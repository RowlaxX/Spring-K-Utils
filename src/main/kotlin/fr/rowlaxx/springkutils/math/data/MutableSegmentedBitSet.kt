package fr.rowlaxx.springkutils.math.data

import java.util.TreeMap

/**
 * A mutable version of [SegmentedBitSet].
 */
class MutableSegmentedBitSet internal constructor(
    content: TreeMap<Long, Long>
) : SegmentedBitSet(content) {
    /**
     * Creates an empty MutableSegmentedBitSet.
     */
    constructor() : this(TreeMap<Long, Long>())

    /**
     * Adds the specified number to this bit set.
     */
    fun add(number: Long) {
        addAll(number..number)
    }

    /**
     * Adds all numbers in the specified range to this bit set.
     */
    fun addAll(range: LongRange) {
        if (range.isEmpty()) return
        var newStart = range.first
        var newEnd = range.last

        // Find overlapping or adjacent segments
        // An adjacent segment to start is one that ends at start - 1
        val floor = if (newStart > Long.MIN_VALUE) content.floorEntry(newStart - 1) else content.floorEntry(newStart)
        if (floor != null && floor.value >= newStart - 1) {
            newStart = minOf(newStart, floor.key)
            newEnd = maxOf(newEnd, floor.value)
            content.remove(floor.key)
        }

        // Find segments that start within or adjacent to the new range
        var ceiling = content.ceilingEntry(newStart)
        while (ceiling != null && (newEnd == Long.MAX_VALUE || ceiling.key <= newEnd + 1)) {
            newEnd = maxOf(newEnd, ceiling.value)
            content.remove(ceiling.key)
            ceiling = content.ceilingEntry(newStart)
        }

        content[newStart] = newEnd
    }

    /**
     * Removes the specified number from this bit set.
     */
    fun remove(number: Long) {
        removeAll(number..number)
    }

    /**
     * Removes all numbers in the specified range from this bit set.
     */
    fun removeAll(range: LongRange) {
        if (range.isEmpty()) return
        val start = range.first
        val end = range.last

        val floor = content.floorEntry(start)
        if (floor != null && floor.value >= start) {
            val oldEnd = floor.value
            if (floor.key < start) {
                content[floor.key] = start - 1
            } else {
                content.remove(floor.key)
            }
            
            if (oldEnd > end) {
                content[end + 1] = oldEnd
            }
        }

        var ceiling = content.ceilingEntry(start)
        while (ceiling != null && ceiling.key <= end) {
            val oldEnd = ceiling.value
            content.remove(ceiling.key)
            if (oldEnd > end) {
                content[end + 1] = oldEnd
                break
            }
            ceiling = content.ceilingEntry(start)
        }
    }

    /**
     * Flips the specified number in this bit set (adds it if absent, removes it if present).
     */
    fun flip(number: Long) {
        flipAll(number..number)
    }

    /**
     * Flips all numbers in the specified range in this bit set.
     */
    fun flipAll(range: LongRange) {
        if (range.isEmpty()) return
        val intersect = subset(range)
        removeAll(range)
        
        var current = range.first
        intersect.content.forEach { (s, e) ->
            if (current < s) {
                addAll(current..s - 1)
            }
            current = e + 1
        }
        if (current <= range.last) {
            addAll(current..range.last)
        }
    }

    /**
     * Sets the presence of the specified number in this bit set.
     */
    fun set(number: Long, present: Boolean) {
        if (present) add(number) else remove(number)
    }

    /**
     * Sets the presence of all numbers in the specified range in this bit set.
     */
    fun setAll(range: LongRange, present: Boolean) {
        if (present) addAll(range) else removeAll(range)
    }
    
    /**
     * Shifts all numbers in this bit set to the right by the specified count.
     */
    fun shiftRight(count: Long) {
        if (count == 0L) return
        if (count < 0) return shiftLeft(-count)
        val entries = content.toList()
        content.clear()
        entries.forEach { (start, end) ->
            content[start + count] = end + count
        }
    }
    
    /**
     * Shifts all numbers in this bit set to the left by the specified count.
     */
    fun shiftLeft(count: Long) {
        if (count == 0L) return
        if (count < 0) return shiftRight(-count)
        val entries = content.toList()
        content.clear()
        entries.forEach { (start, end) ->
            content[start - count] = end - count
        }
    }
}