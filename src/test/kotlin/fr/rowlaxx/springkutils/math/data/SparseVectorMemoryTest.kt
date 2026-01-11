package fr.rowlaxx.springkutils.math.data

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SparseVectorMemoryTest {

    private fun getMemoryUsage(): Long {
        System.gc()
        Thread.sleep(100)
        System.gc()
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }

    @Test
    fun testSparseMemoryEfficiency() {
        val index = 1_000_000
        val value = 42.0
        
        // Measure baseline
        val baseline = getMemoryUsage()
        
        // Create sparse vector
        val vector = MutableSparseVector()
        vector[index] = value
        
        val sparseUsage = getMemoryUsage() - baseline
        println("SparseVector usage for 1 element at $index: $sparseUsage bytes")
        
        // A dense array of 1,000,001 doubles would take ~8MB
        val expectedDenseSize = (index + 1) * 8L
        assertTrue(sparseUsage < expectedDenseSize / 100, "SparseVector should use significantly less memory than a dense array for sparse data")
    }

    @Test
    fun testManySparseElements() {
        val count = 10_000
        val baseline = getMemoryUsage()
        
        val vector = MutableSparseVector()
        for (i in 0 until count) {
            vector[i * 100] = i.toDouble()
        }
        
        val sparseUsage = getMemoryUsage() - baseline
        println("SparseVector usage for $count elements: $sparseUsage bytes")
        
        // Each TreeMap entry has overhead, but it should still be better than a dense array of size 1,000,000
        val denseSize = 1_000_000 * 8L
        assertTrue(sparseUsage < denseSize, "SparseVector should be more efficient than a dense array for 1% occupancy")
    }
}
