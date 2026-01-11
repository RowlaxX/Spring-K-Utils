package fr.rowlaxx.springkutils.math.data

import org.junit.jupiter.api.Test
import java.util.*
import kotlin.system.measureTimeMillis
import org.junit.jupiter.api.Assertions.*

class SegmentedBitSetPerformanceTest {

    @Test
    fun testLargeScaleOperations() {
        val count = 100_000
        val bitset = MutableSegmentedBitSet()

        println("Performance test with $count segments:")

        val addTime = measureTimeMillis {
            for (i in 0L until count.toLong()) {
                bitset.addAll((i * 10)..(i * 10 + 5))
            }
        }
        println("Add $count segments: ${addTime}ms")
        assertEquals(count.toLong(), bitset.content.size.toLong())
        assertEquals(count * 6L, bitset.size())

        val containsTime = measureTimeMillis {
            for (i in 0L until count.toLong()) {
                assertTrue(bitset.contains(i * 10 + 3))
                assertFalse(bitset.contains(i * 10 + 7))
            }
        }
        println("Contains $count checks: ${containsTime}ms")

        val removeTime = measureTimeMillis {
            for (i in 0L until count.toLong()) {
                bitset.remove(i * 10 + 3)
            }
        }
        println("Remove $count elements: ${removeTime}ms")
        assertEquals(count * 2L, bitset.content.size.toLong())
        assertEquals(count * 5L, bitset.size())

        val unionTime = measureTimeMillis {
            val other = MutableSegmentedBitSet()
            for (i in 0L until count.toLong()) {
                other.addAll((i * 10 + 6)..(i * 10 + 9))
            }
            bitset.union(other)
        }
        println("Union with $count segments: ${unionTime}ms")
    }

    @Test
    fun testDeepEntryRemoval() {
        val bitset = MutableSegmentedBitSet()
        val largeRangeStart = 0L
        val largeRangeEnd = 1_000_000L
        bitset.addAll(largeRangeStart..largeRangeEnd)

        val removeCount = 100_000
        val removeTime = measureTimeMillis {
            for (i in 0L until removeCount.toLong()) {
                // Remove every 10th element to create many holes
                bitset.remove(i * 10)
            }
        }
        println("Remove $removeCount elements from 1M range: ${removeTime}ms")
        assertTrue(bitset.content.size > removeCount - 10)
    }

    @Test
    fun testRandomMutations() {
        val bitset = MutableSegmentedBitSet()
        val random = Random(42)
        val iterations = 50_000
        
        val time = measureTimeMillis {
            repeat(iterations) {
                val start = random.nextLong(0, 1_000_000)
                val end = start + random.nextLong(0, 100)
                when (random.nextInt(3)) {
                    0 -> bitset.addAll(start..end)
                    1 -> bitset.removeAll(start..end)
                    2 -> bitset.flipAll(start..end)
                }
            }
        }
        println("Random mutations ($iterations iterations): ${time}ms")
    }
}
