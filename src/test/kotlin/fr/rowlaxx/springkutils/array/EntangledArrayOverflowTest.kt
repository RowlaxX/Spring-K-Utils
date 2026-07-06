package fr.rowlaxx.springkutils.array

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Overflow / extreme-value regression tests for the entangled-array family.
 *
 * The merge paths of [IntIntEntangledArray] and [IntDoubleEntangledArray] decide dense-vs-sparse from
 * `span = maxLevel - minLevel`. With `Int` levels at the extremes (`Int.MIN_VALUE`..`Int.MAX_VALUE`)
 * that difference is ~2^32 and would wrap if computed in `Int`; the implementations widen to `Long`
 * (`maxLevel.toLong() - minLevel`), so these tests pin that the span never overflows — the merge takes
 * the sparse path and stays correct rather than crashing or dropping levels.
 *
 * [DoubleDoubleEntangledArray] (Double levels, heap merge) and [MutableLongObjectEntangledArray] (binary-search
 * map) compute no integer level span at all; the cases here simply confirm they stay exact at the
 * value extremes.
 */
class EntangledArrayOverflowTest {

    private fun IntIntEntangledArray.toMap(): Map<Int, Int> =
        toLevelValueList().associate { (level, value) -> level.toInt() to value.toInt() }

    private fun IntDoubleEntangledArray.toMap(): Map<Int, Double> =
        toLevelValueList().associate { (level, value) -> level.toInt() to value.toDouble() }

    private fun DoubleDoubleEntangledArray.toMap(): Map<Double, Double> =
        toLevelValueList().associate { (level, value) -> level.toDouble() to value.toDouble() }

    // ---------------------------------------------------------------------------------------------
    // IntIntEntangledArray — Int level span at the extremes must not overflow
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `IntInt sum across Int MIN and MAX levels does not overflow`() {
        val a = IntIntEntangledArray(listOf(Int.MIN_VALUE to 3, 0 to 5))
        val b = IntIntEntangledArray(listOf(Int.MAX_VALUE to 7, 0 to 2))

        val sum = IntIntEntangledArray.sum(listOf(a, b))

        assertEquals(mapOf(Int.MIN_VALUE to 3, 0 to 7, Int.MAX_VALUE to 7), sum.toMap())
    }

    @Test
    fun `IntInt cumulativeSet across Int MIN and MAX levels does not overflow`() {
        val a = IntIntEntangledArray(listOf(Int.MIN_VALUE to 3, 0 to 5))
        val b = IntIntEntangledArray(listOf(0 to 9, Int.MAX_VALUE to 7)) // last writer wins at level 0

        val set = IntIntEntangledArray.cumulativeSet(listOf(a, b))

        assertEquals(mapOf(Int.MIN_VALUE to 3, 0 to 9, Int.MAX_VALUE to 7), set.toMap())
    }

    // ---------------------------------------------------------------------------------------------
    // IntDoubleEntangledArray — same Int level span guard
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `IntDouble sum across Int MIN and MAX levels does not overflow`() {
        val a = IntDoubleEntangledArray(listOf(Int.MIN_VALUE to 1.5, 0 to 2.0))
        val b = IntDoubleEntangledArray(listOf(Int.MAX_VALUE to 4.0, 0 to 0.5))

        val sum = IntDoubleEntangledArray.sum(listOf(a, b))

        assertEquals(mapOf(Int.MIN_VALUE to 1.5, 0 to 2.5, Int.MAX_VALUE to 4.0), sum.toMap())
    }

    // ---------------------------------------------------------------------------------------------
    // DoubleDoubleEntangledArray — no integer span; stays exact at extreme Double levels
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `DoubleDouble sum across extreme Double levels stays exact`() {
        val a = DoubleDoubleEntangledArray(listOf(-1e300 to 3.0, 0.0 to 5.0))
        val b = DoubleDoubleEntangledArray(listOf(1e300 to 7.0, 0.0 to 2.0))

        val sum = DoubleDoubleEntangledArray.sum(listOf(a, b))

        assertEquals(mapOf(-1e300 to 3.0, 0.0 to 7.0, 1e300 to 7.0), sum.toMap())
    }

    // ---------------------------------------------------------------------------------------------
    // LongObjectEntangledArray — binary-search map stays correct at the Long key extremes
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `LongObject stores and retrieves keys at the Long extremes`() {
        val map = MutableLongObjectEntangledArray<String>()
        map.put(Long.MIN_VALUE, "min")
        map.put(0L, "zero")
        map.put(Long.MAX_VALUE, "max")

        assertEquals(3, map.size)
        assertEquals("min", map[Long.MIN_VALUE])
        assertEquals("zero", map[0L])
        assertEquals("max", map[Long.MAX_VALUE])
        assertEquals(Long.MIN_VALUE, map.firstKey)
        assertEquals(Long.MAX_VALUE, map.lastKey)
    }

    @Test
    fun `LongObject range queries are correct across the Long extremes`() {
        val map = MutableLongObjectEntangledArray<String>()
        map.put(Long.MIN_VALUE, "min")
        map.put(0L, "zero")
        map.put(Long.MAX_VALUE, "max")

        // counts split around 0 must not be skewed by any min/max difference overflowing
        assertEquals(1, map.countAfter(0L, inclusive = false))
        assertEquals(2, map.countBefore(0L, inclusive = true))
        assertEquals("zero", map.lower(Long.MAX_VALUE))
        assertEquals("min", map.lower(0L))
    }
}
