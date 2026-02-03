package fr.rowlaxx.springkutils.math.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*

class SegmentedBitSetTest {

    @Test
    fun testBasicAddAndContains() {
        val bitset = MutableSegmentedBitSet()
        bitset.add(1)
        bitset.add(2)
        bitset.add(4)
        
        assertTrue(bitset.contains(1))
        assertTrue(bitset.contains(2))
        assertFalse(bitset.contains(3))
        assertTrue(bitset.contains(4))
        assertEquals(3, bitset.size())
    }

    @Test
    fun testAddAllRange() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(0L..9L)
        
        assertTrue(bitset.contains(0))
        assertTrue(bitset.contains(5))
        assertTrue(bitset.contains(9))
        assertFalse(bitset.contains(10))
        assertEquals(10, bitset.size())
    }

    @Test
    fun testMergingSegments() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(0L..1L) // 0, 1
        bitset.addAll(4L..5L) // 4, 5
        assertEquals(2, bitset.content.size)
        
        bitset.addAll(2L..3L) // merge 0-1 and 4-5 into 0-5
        assertEquals(1, bitset.content.size)
        assertTrue(bitset.containsAll(0L..5L))
    }

    @Test
    fun testRemove() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(0L..9L)
        bitset.remove(5)
        
        assertTrue(bitset.contains(4))
        assertFalse(bitset.contains(5))
        assertTrue(bitset.contains(6))
        assertEquals(9, bitset.size())
        assertEquals(2, bitset.content.size)
    }

    @Test
    fun testRemoveAllRange() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(0L..9L)
        bitset.removeAll(2L..7L)
        
        assertTrue(bitset.contains(1))
        assertFalse(bitset.contains(2))
        assertFalse(bitset.contains(7))
        assertTrue(bitset.contains(8))
        assertEquals(4, bitset.size()) // 0, 1, 8, 9
    }

    @Test
    fun testFlip() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(0L..4L)
        bitset.flip(4)
        bitset.flip(5)
        
        assertTrue(bitset.contains(3))
        assertFalse(bitset.contains(4))
        assertTrue(bitset.contains(5))
        assertFalse(bitset.contains(6))
    }

    @Test
    fun testUnion() {
        val bs1 = MutableSegmentedBitSet()
        bs1.addAll(0L..4L)
        val bs2 = MutableSegmentedBitSet()
        bs2.addAll(3L..7L)
        
        val union = bs1 union bs2
        assertTrue(union.containsAll(0L..7L))
        assertEquals(8, union.size())
    }

    @Test
    fun testIntersect() {
        val bs1 = MutableSegmentedBitSet()
        bs1.addAll(0L..4L)
        val bs2 = MutableSegmentedBitSet()
        bs2.addAll(3L..7L)
        
        val intersect = bs1 intersect bs2
        assertFalse(intersect.contains(2))
        assertTrue(intersect.contains(3))
        assertTrue(intersect.contains(4))
        assertFalse(intersect.contains(5))
        assertEquals(2, intersect.size())
    }

    @Test
    fun testXor() {
        val bs1 = MutableSegmentedBitSet()
        bs1.addAll(0L..4L)
        val bs2 = MutableSegmentedBitSet()
        bs2.addAll(3L..7L)
        
        val xor = bs1 xor bs2
        assertTrue(xor.containsAll(0L..2L))
        assertFalse(xor.contains(3))
        assertFalse(xor.contains(4))
        assertTrue(xor.containsAll(5L..7L))
        assertEquals(6, xor.size())
    }

    @Test
    fun testShift() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(0L..1L) // 0, 1
        
        bitset.shiftRight(2)
        assertFalse(bitset.contains(0))
        assertTrue(bitset.contains(2))
        assertTrue(bitset.contains(3))
        
        bitset.shiftLeft(1)
        assertTrue(bitset.contains(1))
        assertTrue(bitset.contains(2))
        assertFalse(bitset.contains(3))
    }

    @Test
    fun testNextPrevious() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(10L..20L)
        bitset.addAll(30L..40L)
        
        assertEquals(10, bitset.next(0))
        assertEquals(10, bitset.next(10))
        assertEquals(15, bitset.next(15))
        assertEquals(20, bitset.next(20))
        assertEquals(30, bitset.next(21))
        
        assertEquals(20, bitset.previous(25))
        assertEquals(40, bitset.previous(50))
        assertEquals(10, bitset.previous(10))
        
        assertEquals(21, bitset.nextAbsent(15))
        assertEquals(0, bitset.nextAbsent(0)) // Wait, 0 is absent
        
        assertEquals(9, bitset.previousAbsent(15))
        assertEquals(41, bitset.nextAbsent(40))
    }

    @Test
    fun testHasNextHasPrevious() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(10L..20L)
        bitset.addAll(40L..50L)

        // hasNext
        assertTrue(bitset.hasNext(0))
        assertTrue(bitset.hasNext(10))
        assertTrue(bitset.hasNext(15))
        assertTrue(bitset.hasNext(20))
        assertTrue(bitset.hasNext(21))
        assertTrue(bitset.hasNext(40))
        assertTrue(bitset.hasNext(50))
        assertFalse(bitset.hasNext(51))

        // hasPrevious
        assertFalse(bitset.hasPrevious(0))
        assertFalse(bitset.hasPrevious(9))
        assertTrue(bitset.hasPrevious(10))
        assertTrue(bitset.hasPrevious(15))
        assertTrue(bitset.hasPrevious(20))
        assertTrue(bitset.hasPrevious(21))
        assertTrue(bitset.hasPrevious(39))
        assertTrue(bitset.hasPrevious(40))
        assertTrue(bitset.hasPrevious(50))
        assertTrue(bitset.hasPrevious(100))

        // Empty
        val empty = SegmentedBitSet()
        assertFalse(empty.hasNext(0))
        assertFalse(empty.hasPrevious(0))
    }

    @Test
    fun testSubset() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(0L..100L)
        
        val subset = bitset.subset(10L..20L)
        assertEquals(11, subset.size())
        assertTrue(subset.containsAll(10L..20L))
        assertFalse(subset.contains(9))
        assertFalse(subset.contains(21))
    }

    @Test
    fun testOpenEndedContainsAll() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(10L..15L)

        assertTrue(bitset.containsAll(10L..15L))
        assertTrue(bitset.containsAll(10L..<16L))
    }

    @Test
    fun testSetAll() {
        val bitset = MutableSegmentedBitSet()
        
        // Test setAll(true)
        bitset.setAll(10L..20L, true)
        assertTrue(bitset.containsAll(10L..20L))
        assertEquals(11, bitset.size())
        
        // Test setAll(false)
        bitset.setAll(15L..25L, false)
        assertTrue(bitset.containsAll(10L..14L))
        assertFalse(bitset.contains(15))
        assertFalse(bitset.contains(20))
        assertEquals(5, bitset.size())
        
        // Test setAll(true) with overlap
        bitset.setAll(5L..15L, true)
        assertTrue(bitset.containsAll(5L..15L))
        assertEquals(11, bitset.size()) // 5..15
        
        // Test setAll with large values
        bitset.setAll(Long.MIN_VALUE..Long.MIN_VALUE + 10, true)
        bitset.setAll(Long.MAX_VALUE - 10..Long.MAX_VALUE, true)
        assertTrue(bitset.contains(Long.MIN_VALUE))
        assertTrue(bitset.contains(Long.MAX_VALUE))
        
        bitset.setAll(Long.MIN_VALUE..Long.MAX_VALUE, false)
        assertEquals(0, bitset.size())
        assertTrue(bitset.content.isEmpty())
    }

    @Test
    fun testDeepRemoval() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(0L..100L)
        
        // Remove 10, 20, 30... 90
        for (i in 10..90 step 10) {
            bitset.remove(i.toLong())
        }
        
        assertEquals(101 - 9, bitset.size())
        assertEquals(10, bitset.content.size) // [0..9, 11..19, ..., 91..100]
        
        for (i in 0..100) {
            if (i % 10 == 0 && i != 0 && i != 100) {
                assertFalse(bitset.contains(i.toLong()))
            } else {
                assertTrue(bitset.contains(i.toLong()))
            }
        }
    }

    @Test
    fun testRandomMutationsAgainstReference() {
        val bitset = MutableSegmentedBitSet()
        val reference = java.util.BitSet()
        val random = java.util.Random(42)
        val range = 0..1000
        
        repeat(1000) {
            val start = random.nextInt(range.last + 1)
            val length = random.nextInt(20)
            val end = minOf(range.last, start + length)
            val longRange = start.toLong()..end.toLong()
            
            when (random.nextInt(3)) {
                0 -> {
                    bitset.addAll(longRange)
                    reference.set(start, end + 1)
                }
                1 -> {
                    bitset.removeAll(longRange)
                    reference.clear(start, end + 1)
                }
                2 -> {
                    bitset.flipAll(longRange)
                    reference.flip(start, end + 1)
                }
            }
            
            assertEquals(reference.cardinality().toLong(), bitset.size())
            for (i in range) {
                assertEquals(reference.get(i), bitset.contains(i.toLong()), "Mismatch at bit $i")
            }
        }
    }

    @Test
    fun testLargeSetOperations() {
        val bs1 = MutableSegmentedBitSet()
        val bs2 = MutableSegmentedBitSet()
        
        // bs1: [0..1, 4..5, 8..9, ...]
        // bs2: [2..3, 6..7, 10..11, ...]
        for (i in 0 until 1000 step 4) {
            bs1.addAll(i.toLong()..(i + 1).toLong())
            bs2.addAll((i + 2).toLong()..(i + 3).toLong())
        }
        
        val union = bs1 union bs2
        assertEquals(1000, union.size())
        assertTrue(union.containsAll(0L..999L))
        assertEquals(1, union.content.size)
        
        val intersect = bs1 intersect bs2
        assertEquals(0, intersect.size())
        
        val xor = bs1 xor bs2
        assertEquals(1000, xor.size())
        assertEquals(1, xor.content.size)
        
        // Overlapping case
        val bs3 = MutableSegmentedBitSet()
        // bs3: [1..2, 5..6, 9..10, ...]
        for (i in 0 until 1000 step 4) {
            bs3.addAll((i + 1).toLong()..(i + 2).toLong())
        }
        
        val intersect2 = bs1 intersect bs3
        // bs1: [0..1, 4..5, 8..9]
        // bs3: [1..2, 5..6, 9..10]
        // Intersection: [1, 5, 9, ...]
        assertEquals(250, intersect2.size())
        for (i in 0 until 1000 step 4) {
            assertTrue(intersect2.contains(i.toLong() + 1))
            assertFalse(intersect2.contains(i.toLong()))
            assertFalse(intersect2.contains(i.toLong() + 2))
        }
    }

    @Test
    fun testEmptyBitSet() {
        val bitset = SegmentedBitSet()
        assertEquals(0, bitset.size())
        assertThrows<NoSuchElementException> { bitset.first() }
        assertThrows<NoSuchElementException> { bitset.last() }
        assertThrows<NoSuchElementException> { bitset.next(0) }
        assertThrows<NoSuchElementException> { bitset.previous(0) }
        assertEquals(0, bitset.nextAbsent(0))
        assertEquals(0, bitset.previousAbsent(0))
        assertFalse(bitset.contains(0))
        assertTrue(bitset.containsAll(LongRange.EMPTY))
        assertFalse(bitset.containsAny(0L..100L))
        assertFalse(bitset.containsAny(LongRange.EMPTY))
    }

    @Test
    fun testContainsAny() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(10L..20L)
        bitset.addAll(40L..50L)

        // Fully inside a segment
        assertTrue(bitset.containsAny(12L..15L))
        // Overlap at the start of a segment
        assertTrue(bitset.containsAny(5L..12L))
        // Overlap at the end of a segment
        assertTrue(bitset.containsAny(18L..25L))
        // Spanning multiple segments
        assertTrue(bitset.containsAny(15L..45L))
        // Exactly one segment
        assertTrue(bitset.containsAny(10L..20L))
        // Touches one segment at the boundary
        assertTrue(bitset.containsAny(20L..25L))
        assertTrue(bitset.containsAny(5L..10L))

        // In the gap between segments
        assertFalse(bitset.containsAny(21L..39L))
        // Before all segments
        assertFalse(bitset.containsAny(0L..9L))
        // After all segments
        assertFalse(bitset.containsAny(51L..100L))

        // Empty range
        assertFalse(bitset.containsAny(LongRange.EMPTY))
    }

    @Test
    fun testContainsAnyWithSingleBit() {
        val bitset = MutableSegmentedBitSet()
        bitset.add(100L)

        assertTrue(bitset.containsAny(100L..100L))
        assertFalse(bitset.containsAny(99L..99L))
        assertFalse(bitset.containsAny(101L..101L))
        assertTrue(bitset.containsAny(99L..101L))
    }

    @Test
    fun testLargeValues() {
        val bitset = MutableSegmentedBitSet()
        val large = Long.MAX_VALUE - 10
        bitset.addAll(large..Long.MAX_VALUE)
        
        assertTrue(bitset.contains(large))
        assertTrue(bitset.contains(Long.MAX_VALUE))
        assertEquals(11, bitset.size())
        assertEquals(large, bitset.first())
        assertEquals(Long.MAX_VALUE, bitset.last())
        
        bitset.addAll(Long.MIN_VALUE..Long.MIN_VALUE + 10)
        assertTrue(bitset.contains(Long.MIN_VALUE))
        assertEquals(22, bitset.size())
        assertEquals(Long.MIN_VALUE, bitset.first())

        bitset.addAll(Long.MIN_VALUE..Long.MAX_VALUE)
        assertEquals(1, bitset.content.size)
        // size() will overflow if it returns Long. Actually, size of MIN..MAX is 2^64 which doesn't fit in Long.
        // My size() returns Long, so it will probably return 0 due to overflow.
        // Long.MAX_VALUE - Long.MIN_VALUE + 1 = -1 + 1 = 0 in 64-bit signed arithmetic.
    }

    @Test
    fun testExhaustiveNextPrevious() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(10L..20L)
        bitset.addAll(40L..50L)
        bitset.addAll(70L..80L)

        // next
        assertEquals(10, bitset.next(0))
        assertEquals(10, bitset.next(10))
        assertEquals(20, bitset.next(20))
        assertEquals(40, bitset.next(21))
        assertEquals(40, bitset.next(40))
        assertEquals(70, bitset.next(51))
        assertThrows<NoSuchElementException> { bitset.next(81) }

        // nextOrNull
        assertEquals(10, bitset.nextOrNull(0))
        assertEquals(10, bitset.nextOrNull(10))
        assertEquals(40, bitset.nextOrNull(21))
        assertNull(bitset.nextOrNull(81))

        // previous
        assertEquals(80, bitset.previous(100))
        assertEquals(80, bitset.previous(80))
        assertEquals(70, bitset.previous(70))
        assertEquals(50, bitset.previous(69))
        assertEquals(20, bitset.previous(39))
        assertThrows<NoSuchElementException> { bitset.previous(9) }

        // previousOrNull
        assertEquals(80, bitset.previousOrNull(100))
        assertEquals(80, bitset.previousOrNull(80))
        assertEquals(20, bitset.previousOrNull(39))
        assertNull(bitset.previousOrNull(9))

        // nextAbsent
        assertEquals(0, bitset.nextAbsent(0))
        assertEquals(9, bitset.nextAbsent(9))
        assertEquals(21, bitset.nextAbsent(10))
        assertEquals(21, bitset.nextAbsent(20))
        assertEquals(21, bitset.nextAbsent(21))
        assertEquals(51, bitset.nextAbsent(50))
        assertEquals(81, bitset.nextAbsent(80))

        // nextAbsentOrNull
        assertEquals(0, bitset.nextAbsentOrNull(0))
        assertEquals(21, bitset.nextAbsentOrNull(15))
        assertEquals(81, bitset.nextAbsentOrNull(80))

        // previousAbsent
        assertEquals(100, bitset.previousAbsent(100))
        assertEquals(81, bitset.previousAbsent(81))
        assertEquals(69, bitset.previousAbsent(70))
        assertEquals(69, bitset.previousAbsent(80))
        assertEquals(39, bitset.previousAbsent(40))
        assertEquals(9, bitset.previousAbsent(10))

        // previousAbsentOrNull
        assertEquals(100, bitset.previousAbsentOrNull(100))
        assertEquals(69, bitset.previousAbsentOrNull(70))
        assertEquals(9, bitset.previousAbsentOrNull(10))
    }

    @Test
    fun testAbsentBoundaryCases() {
        val bitset = MutableSegmentedBitSet()
        
        // Long.MAX_VALUE
        bitset.addAll(Long.MAX_VALUE - 1..Long.MAX_VALUE)
        assertNull(bitset.nextAbsentOrNull(Long.MAX_VALUE))
        assertThrows<NoSuchElementException> { bitset.nextAbsent(Long.MAX_VALUE) }
        
        // Long.MIN_VALUE
        bitset.addAll(Long.MIN_VALUE..Long.MIN_VALUE + 1)
        assertNull(bitset.previousAbsentOrNull(Long.MIN_VALUE))
        assertThrows<NoSuchElementException> { bitset.previousAbsent(Long.MIN_VALUE) }
    }

    @Test
    fun testComplexMergingAndSplitting() {
        val bitset = MutableSegmentedBitSet()
        // Create many small segments: [0,0], [2,2], [4,4], ..., [20,20]
        for (i in 0L..20L step 2) {
            bitset.add(i)
        }
        assertEquals(11, bitset.size())
        assertEquals(11, bitset.content.size)

        // Merge them all by filling the gaps
        for (i in 1L..19L step 2) {
            bitset.add(i)
        }
        assertEquals(21, bitset.size())
        assertEquals(1, bitset.content.size)
        assertTrue(bitset.containsAll(0L..20L))

        // Split it into two
        bitset.remove(10)
        assertEquals(20, bitset.size())
        assertEquals(2, bitset.content.size)
        assertFalse(bitset.contains(10))
        assertTrue(bitset.containsAll(0L..9L))
        assertTrue(bitset.containsAll(11L..20L))

        // Complex removeAll
        bitset.addAll(0L..100L)
        bitset.removeAll(10L..90L)
        assertEquals(20, bitset.size()) // 0-9 and 91-100
        assertEquals(2, bitset.content.size)
    }

    @Test
    fun testSetOperationsExhaustive() {
        val bs1 = MutableSegmentedBitSet()
        bs1.addAll(0L..10L)
        bs1.addAll(20L..30L)
        bs1.addAll(40L..50L)

        val bs2 = MutableSegmentedBitSet()
        bs2.addAll(5L..25L)
        bs2.addAll(45L..60L)

        // Union: 0-30, 40-60
        val union = bs1 or bs2
        assertTrue(union.containsAll(0L..30L))
        assertTrue(union.containsAll(40L..60L))
        assertFalse(union.contains(31))
        assertEquals(31 + 21, union.size())

        // Intersect: 5-10, 20-25, 45-50
        val intersect = bs1 and bs2
        assertTrue(intersect.containsAll(5L..10L))
        assertTrue(intersect.containsAll(20L..25L))
        assertTrue(intersect.containsAll(45L..50L))
        assertFalse(intersect.contains(11))
        assertFalse(intersect.contains(19))
        assertEquals(6 + 6 + 6, intersect.size())

        // XOR: (union) - (intersect)
        // (0-30, 40-60) - (5-10, 20-25, 45-50)
        // 0-4, 11-19, 26-30, 40-44, 51-60
        val xor = bs1 xor bs2
        assertTrue(xor.containsAll(0L..4L))
        assertTrue(xor.containsAll(11L..19L))
        assertTrue(xor.containsAll(26L..30L))
        assertTrue(xor.containsAll(40L..44L))
        assertTrue(xor.containsAll(51L..60L))
        assertFalse(xor.contains(5))
        assertFalse(xor.contains(20))
        assertFalse(xor.contains(45))
        assertEquals(5 + 9 + 5 + 5 + 10, xor.size())
    }

    @Test
    fun testShiftingExhaustive() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(10L..20L)
        
        // Right shift
        val shiftedRight = bitset.rightShifted(100)
        assertTrue(shiftedRight.containsAll(110L..120L))
        assertFalse(shiftedRight.contains(10))
        
        // Left shift
        val shiftedLeft = bitset.leftShifted(50)
        assertTrue(shiftedLeft.containsAll(-40L..-30L))
        
        // Mutable shifts
        val mutable = bitset.copy()
        mutable.shiftRight(1000)
        assertTrue(mutable.containsAll(1010L..1020L))
        mutable.shiftLeft(2000)
        assertTrue(mutable.containsAll(-990L..-980L))
    }

    @Test
    fun testFlipped() {
        val bitset = SegmentedBitSet()
        assertThrows<UnsupportedOperationException> {
            bitset.flipped()
        }
    }

    @Test
    fun testImmutableView() {
        val original = MutableSegmentedBitSet()
        original.add(10)
        
        val view = original.immutableView()
        assertTrue(view.contains(10))
        
        original.add(20)
        assertTrue(view.contains(20)) // Should reflect changes
        
        original.remove(10)
        assertFalse(view.contains(10)) // Should reflect changes
        
        // Verify it's actually a SegmentedBitSet (immutable type)
        assertFalse(view is MutableSegmentedBitSet)
        assertEquals(SegmentedBitSet::class, view::class)
    }

    @Test
    fun testImmutableCopy() {
        val original = MutableSegmentedBitSet()
        original.add(10)

        val copy = original.immutableCopy()
        assertTrue(copy.contains(10))

        original.add(20)
        // If it's a true copy, it should NOT contain 20
        // Currently it does because it shares the TreeMap
        assertFalse(copy.contains(20), "Copy should not reflect changes in original")

        original.remove(10)
        assertTrue(copy.contains(10), "Copy should not reflect changes in original")

        // Verify it's actually a SegmentedBitSet (immutable type)
        assertFalse(copy is MutableSegmentedBitSet)
        assertEquals(SegmentedBitSet::class, copy::class)
    }

    @Test
    fun testIssueScenario() {
        val bitset = MutableSegmentedBitSet()
        // Let's say absent values are ..., 98, 99, 100
        // If we add nothing, everything is absent.
        // If input is 100, previous absent should be 100.
        assertEquals(100, bitset.previousAbsent(100))

        // Let's add 100 to the set. Now 100 is present.
        bitset.add(100)
        // Previous absent <= 100 should be 99.
        assertEquals(99, bitset.previousAbsent(100))

        // Let's add 90..100 to the set.
        bitset.addAll(90L..100L)
        // Previous absent <= 100 should be 89.
        assertEquals(89, bitset.previousAbsent(100))

        // Let's add everything up to 100.
        bitset.addAll(Long.MIN_VALUE..100L)
        // Now no absent value <= 100.
        assertThrows<NoSuchElementException> { bitset.previousAbsent(100) }
        assertNull(bitset.previousAbsentOrNull(100))
    }

    @Test
    fun testPreviousAbsentComprehensive() {
        val bitset = MutableSegmentedBitSet()
        // [10, 20], [30, 40]
        bitset.addAll(10L..20L)
        bitset.addAll(30L..40L)

        // Before first segment
        assertEquals(9, bitset.previousAbsent(9))
        assertEquals(5, bitset.previousAbsent(5))

        // At start of first segment
        assertEquals(9, bitset.previousAbsent(10))

        // Inside first segment
        assertEquals(9, bitset.previousAbsent(15))

        // At end of first segment
        assertEquals(9, bitset.previousAbsent(20))

        // Between segments
        assertEquals(21, bitset.previousAbsent(21))
        assertEquals(25, bitset.previousAbsent(25))
        assertEquals(29, bitset.previousAbsent(29))

        // At start of second segment
        assertEquals(29, bitset.previousAbsent(30))

        // After segments
        assertEquals(41, bitset.previousAbsent(41))
        assertEquals(100, bitset.previousAbsent(100))
    }

    @Test
    fun testBoundaryLongMinValue() {
        val bitset = MutableSegmentedBitSet()

        // Empty set: Long.MIN_VALUE is absent
        assertEquals(Long.MIN_VALUE, bitset.previousAbsent(Long.MIN_VALUE))

        // Add Long.MIN_VALUE
        bitset.add(Long.MIN_VALUE)
        assertThrows<NoSuchElementException> { bitset.previousAbsent(Long.MIN_VALUE) }
        assertNull(bitset.previousAbsentOrNull(Long.MIN_VALUE))

        bitset.addAll(Long.MIN_VALUE..0L)
        assertThrows<NoSuchElementException> { bitset.previousAbsent(0) }
        assertNull(bitset.previousAbsentOrNull(0))
        assertEquals(1, bitset.previousAbsent(1))
    }

    @Test
    fun testBoundaryLongMaxValue() {
        val bitset = MutableSegmentedBitSet()
        // Empty set
        assertEquals(Long.MAX_VALUE, bitset.previousAbsent(Long.MAX_VALUE))

        // Add Long.MAX_VALUE
        bitset.add(Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE - 1, bitset.previousAbsent(Long.MAX_VALUE))
    }

    @Test
    fun testEmptyStatic() {
        assertTrue(SegmentedBitSet.EMPTY.content.isEmpty())
        assertEquals(0, SegmentedBitSet.EMPTY.size())
        assertFalse(SegmentedBitSet.EMPTY.contains(0))
        assertSame(SegmentedBitSet.EMPTY, SegmentedBitSet.EMPTY.immutableView())
    }

    @Test
    fun testForEachRange() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(10L..20L)
        bitset.addAll(30L..40L)
        bitset.add(50L)

        val ranges = mutableListOf<LongRange>()
        bitset.forEachRange { ranges.add(it) }

        assertEquals(3, ranges.size)
        assertEquals(10L..20L, ranges[0])
        assertEquals(30L..40L, ranges[1])
        assertEquals(50L..50L, ranges[2])
    }

    @Test
    fun testForEachAbsentRange() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(10L..20L)
        bitset.addAll(30L..40L)

        val absentRanges = mutableListOf<LongRange>()
        bitset.forEachAbsentRange(0L..50L) { absentRanges.add(it) }

        assertEquals(3, absentRanges.size)
        assertEquals(0L..9L, absentRanges[0])
        assertEquals(21L..29L, absentRanges[1])
        assertEquals(41L..50L, absentRanges[2])
    }

    @Test
    fun testForEachAbsentRangeEmptySet() {
        val bitset = SegmentedBitSet.EMPTY
        val absentRanges = mutableListOf<LongRange>()
        bitset.forEachAbsentRange(10L..20L) { absentRanges.add(it) }

        assertEquals(1, absentRanges.size)
        assertEquals(10L..20L, absentRanges[0])
    }

    @Test
    fun testForEachAbsentRangeFullSet() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(0L..100L)
        val absentRanges = mutableListOf<LongRange>()
        bitset.forEachAbsentRange(10L..20L) { absentRanges.add(it) }

        assertTrue(absentRanges.isEmpty())
    }

    @Test
    fun testForEachAbsentRangeBoundaries() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(1L..Long.MAX_VALUE - 1)
        
        val absentRanges = mutableListOf<LongRange>()
        bitset.forEachAbsentRange { absentRanges.add(it) }
        
        assertEquals(2, absentRanges.size)
        assertEquals(Long.MIN_VALUE..0L, absentRanges[0])
        assertEquals(Long.MAX_VALUE..Long.MAX_VALUE, absentRanges[1])
    }

    @Test
    fun testForEachAbsentRangeStartingAtBoundary() {
        val bitset = MutableSegmentedBitSet()
        bitset.add(Long.MIN_VALUE)
        bitset.add(Long.MAX_VALUE)

        val absentRanges = mutableListOf<LongRange>()
        bitset.forEachAbsentRange { absentRanges.add(it) }

        assertEquals(1, absentRanges.size)
        assertEquals(Long.MIN_VALUE + 1..Long.MAX_VALUE - 1, absentRanges[0])
    }

    @Test
    fun testForEachAbsentRangeWithMultipleSegments() {
        val bitset = MutableSegmentedBitSet()
        for (i in 0..10 step 2) {
            bitset.add(i.toLong())
        }
        // Present: 0, 2, 4, 6, 8, 10
        // Absent in 0..10: 1, 3, 5, 7, 9

        val absentRanges = mutableListOf<LongRange>()
        bitset.forEachAbsentRange(0L..10L) { absentRanges.add(it) }

        assertEquals(5, absentRanges.size)
        assertEquals(1L..1L, absentRanges[0])
        assertEquals(3L..3L, absentRanges[1])
        assertEquals(5L..5L, absentRanges[2])
        assertEquals(7L..7L, absentRanges[3])
        assertEquals(9L..9L, absentRanges[4])
    }

    @Test
    fun testForEachAbsentRangePartialOverlap() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(10L..20L)
        bitset.addAll(30L..40L)

        // Case 1: Range starts inside a segment
        val absentRanges1 = mutableListOf<LongRange>()
        bitset.forEachAbsentRange(15L..35L) { absentRanges1.add(it) }
        assertEquals(1, absentRanges1.size)
        assertEquals(21L..29L, absentRanges1[0])

        // Case 2: Range ends inside a segment
        val absentRanges2 = mutableListOf<LongRange>()
        bitset.forEachAbsentRange(5L..15L) { absentRanges2.add(it) }
        assertEquals(1, absentRanges2.size)
        assertEquals(5L..9L, absentRanges2[0])

        // Case 3: Range strictly between segments
        val absentRanges3 = mutableListOf<LongRange>()
        bitset.forEachAbsentRange(22L..28L) { absentRanges3.add(it) }
        assertEquals(1, absentRanges3.size)
        assertEquals(22L..28L, absentRanges3[0])
    }

    @Test
    fun testForEachAbsentRangeVerySmall() {
        val bitset = MutableSegmentedBitSet()
        bitset.add(10L)
        
        val absentRanges = mutableListOf<LongRange>()
        bitset.forEachAbsentRange(10L..10L) { absentRanges.add(it) }
        assertTrue(absentRanges.isEmpty())
        
        bitset.forEachAbsentRange(11L..11L) { absentRanges.add(it) }
        assertEquals(1, absentRanges.size)
        assertEquals(11L..11L, absentRanges[0])
    }

    @Test
    fun testForEachAbsentRangeLargeGap() {
        val bitset = MutableSegmentedBitSet()
        bitset.add(Long.MIN_VALUE)
        bitset.add(Long.MAX_VALUE)
        
        val absentRanges = mutableListOf<LongRange>()
        bitset.forEachAbsentRange(Long.MIN_VALUE..Long.MAX_VALUE) { absentRanges.add(it) }
        assertEquals(1, absentRanges.size)
        assertEquals(Long.MIN_VALUE + 1..Long.MAX_VALUE - 1, absentRanges[0])
    }

    @Test
    fun testContainsAllBugReproduction() {
        val content = TreeMap<Long, Long>()
        content[0L] = 5L
        content[6L] = 10L
        val bitset = SegmentedBitSet(content)

        assertTrue(bitset.containsAll(0L..10L), "Should contain 0..10 even if split into [0, 5] and [6, 10]")
        assertFalse(bitset.containsAll(0L..11L))
        assertFalse(bitset.containsAll(-1L..10L))
        
        content[12L] = 15L
        val bitset2 = SegmentedBitSet(content)
        assertFalse(bitset2.containsAll(0L..15L), "Should be false because of gap at 11")
        assertTrue(bitset2.containsAll(0L..10L))
        assertTrue(bitset2.containsAll(12L..15L))
    }
}
