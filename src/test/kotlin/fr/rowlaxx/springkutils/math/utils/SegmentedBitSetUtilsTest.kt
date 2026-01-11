package fr.rowlaxx.springkutils.math.utils

import fr.rowlaxx.springkutils.math.data.MutableSegmentedBitSet
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SegmentedBitSetUtilsTest {

    @Test
    fun `test empty bitset serialization`() {
        val bitset = MutableSegmentedBitSet()
        val bytes = SegmentedBitSetUtils.serialize(bitset)
        val deserialized = SegmentedBitSetUtils.deserialize(bytes)
        assertEquals(bitset, deserialized)
        assertEquals(0, deserialized.size())
    }

    @Test
    fun `test single point serialization`() {
        val bitset = MutableSegmentedBitSet()
        bitset.add(42L)
        val bytes = SegmentedBitSetUtils.serialize(bitset)
        val deserialized = SegmentedBitSetUtils.deserialize(bytes)
        assertEquals(bitset, deserialized)
        assertTrue(deserialized.contains(42L))
        assertEquals(1, deserialized.size())
    }

    @Test
    fun `test single large range serialization`() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(100L..1000L)
        val bytes = SegmentedBitSetUtils.serialize(bitset)
        val deserialized = SegmentedBitSetUtils.deserialize(bytes)
        assertEquals(bitset, deserialized)
        assertEquals(901L, deserialized.size())
    }

    @Test
    fun `test multiple disjoint ranges serialization`() {
        val bitset = MutableSegmentedBitSet()
        bitset.addAll(10L..20L)
        bitset.addAll(40L..50L)
        bitset.addAll(100L..200L)
        val bytes = SegmentedBitSetUtils.serialize(bitset)
        val deserialized = SegmentedBitSetUtils.deserialize(bytes)
        assertEquals(bitset, deserialized)
        assertEquals(11 + 11 + 101, deserialized.size())
    }

    @Test
    fun `test complex bitset serialization`() {
        val bitset = MutableSegmentedBitSet()
        for (i in 0L until 1000L step 2) {
            bitset.add(i)
        }
        val bytes = SegmentedBitSetUtils.serialize(bitset)
        val deserialized = SegmentedBitSetUtils.deserialize(bytes)
        assertEquals(bitset, deserialized)
        assertEquals(500L, deserialized.size())
        for (i in 0L until 1000L) {
            assertEquals(i % 2 == 0L, deserialized.contains(i))
        }
    }
}
