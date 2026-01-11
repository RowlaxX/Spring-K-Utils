package fr.rowlaxx.springkutils.math.data

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.util.BitSet

class SegmentedBitSetMemoryTest {

    private fun getMemoryUsage(): Long {
        System.gc()
        Thread.sleep(100)
        System.gc()
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun testSegmentedMemoryEfficiency() {
        val largeIndex = 1_000_000_000L // 1 billion
        
        // Measure baseline
        val baseline = getMemoryUsage()
        
        // Create segmented bitset
        val bitset = MutableSegmentedBitSet()
        bitset.add(largeIndex)
        
        val segmentedUsage = getMemoryUsage() - baseline
        println("SegmentedBitSet usage for 1 bit at $largeIndex: $segmentedUsage bytes")
        
        // A java.util.BitSet for 1 billion bits would take ~125MB
        val bitSetSize = largeIndex / 8
        assertTrue(segmentedUsage < bitSetSize / 1000, "SegmentedBitSet should use significantly less memory than a BitSet for distant bits")
    }

    @Test
    fun testLargeRangeEfficiency() {
        val start = 0L
        val end = 1_000_000_000L // 1 billion bits
        
        val baseline = getMemoryUsage()
        
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(start..end)
        
        val segmentedUsage = getMemoryUsage() - baseline
        println("SegmentedBitSet usage for 1 billion contiguous bits: $segmentedUsage bytes")
        
        // This should only take ONE entry in the TreeMap
        assertTrue(segmentedUsage < 1024 * 1024, "SegmentedBitSet should use very little memory for a single large range")
    }
    
    @Test
    fun testManySegmentsEfficiency() {
        val count = 10_000
        val baseline = getMemoryUsage()
        
        val bitset = MutableSegmentedBitSet()
        for (i in 0L until count.toLong()) {
            bitset.addAll((i * 100)..(i * 100 + 50))
        }
        
        val segmentedUsage = getMemoryUsage() - baseline
        println("SegmentedBitSet usage for $count segments: $segmentedUsage bytes")
        
        // 10,000 segments, each with a TreeMap entry. 
        // TreeMap entry is roughly 40-64 bytes. 10k * 64 = 640KB.
        assertTrue(segmentedUsage < 5 * 1024 * 1024, "SegmentedBitSet should handle many segments reasonably")
    }
}
