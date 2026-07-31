package fr.rowlaxx.springkutils.collection.set

import fr.rowlaxx.springkutils.collection.map.MutableLongLongArrayMap

/**
 * A mutable version of [SegmentedBitSet].
 */
class  MutableSegmentedBitSet internal constructor(
    content: MutableLongLongArrayMap
) : SegmentedBitSet(content) {
    /**
     * Creates an empty MutableSegmentedBitSet.
     */
    constructor() : this(MutableLongLongArrayMap())

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
        val floorKey = if (newStart > Long.MIN_VALUE) content.floorKey(newStart - 1) else content.floorKey(newStart)
        if (floorKey != null) {
            val floorEnd = content.getOrDefault(floorKey, Long.MIN_VALUE)
            if (floorEnd >= newStart - 1) {
                newStart = minOf(newStart, floorKey)
                newEnd = maxOf(newEnd, floorEnd)
                content.remove(floorKey)
            }
        }

        // Find segments that start within or adjacent to the new range
        var ceilingKey = content.ceilingKey(newStart)
        while (ceilingKey != null && (newEnd == Long.MAX_VALUE || ceilingKey <= newEnd + 1)) {
            newEnd = maxOf(newEnd, content.getOrDefault(ceilingKey, Long.MIN_VALUE))
            content.remove(ceilingKey)
            ceilingKey = content.ceilingKey(newStart)
        }

        content.put(newStart, newEnd)
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

        val floorKey = content.floorKey(start)
        if (floorKey != null) {
            val floorEnd = content.getOrDefault(floorKey, Long.MIN_VALUE)
            if (floorEnd >= start) {
                val oldEnd = floorEnd
                if (floorKey < start) {
                    content.put(floorKey, start - 1)
                } else {
                    content.remove(floorKey)
                }

                if (oldEnd > end) {
                    content.put(end + 1, oldEnd)
                }
            }
        }

        var ceilingKey = content.ceilingKey(start)
        while (ceilingKey != null && ceilingKey <= end) {
            val oldEnd = content.getOrDefault(ceilingKey, Long.MIN_VALUE)
            content.remove(ceilingKey)
            if (oldEnd > end) {
                content.put(end + 1, oldEnd)
                break
            }
            ceilingKey = content.ceilingKey(start)
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
        var exhausted = false
        intersect.content.forEach { s, e ->
            if (current < s) {
                addAll(current..s - 1)
            }
            // A segment ending at MAX_VALUE also ends the range: advancing past it would overflow
            // and wrongly re-add everything from MIN_VALUE.
            if (e == Long.MAX_VALUE) {
                exhausted = true
            } else {
                current = e + 1
            }
        }
        if (!exhausted && current <= range.last) {
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
        shiftBy(count)
    }

    /**
     * Shifts all numbers in this bit set to the left by the specified count.
     */
    fun shiftLeft(count: Long) {
        if (count == 0L) return
        if (count < 0) return shiftRight(-count)
        shiftBy(-count)
    }

    /**
     * Snapshots the segments, clears the backing store, and re-inserts every segment shifted by
     * [delta]. A uniform shift preserves the ascending order, so the re-insert is a straight append.
     */
    private fun shiftBy(delta: Long) {
        val n = content.size
        if (n == 0) return
        val starts = LongArray(n)
        val ends = LongArray(n)
        var i = 0
        content.forEach { start, end ->
            starts[i] = start
            ends[i] = end
            i++
        }
        content.clear()
        for (k in 0 until n) {
            content.put(starts[k] + delta, ends[k] + delta)
        }
    }
}
