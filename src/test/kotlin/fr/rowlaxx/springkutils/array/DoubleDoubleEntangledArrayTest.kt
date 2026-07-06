package fr.rowlaxx.springkutils.array

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/**
 * Black-box tests for [DoubleDoubleEntangledArray] exercised entirely through its public API
 * (constructor, [DoubleDoubleEntangledArray.times], [DoubleDoubleEntangledArray.div], `size`,
 * `firstLevel`, `lastLevel`, [DoubleDoubleEntangledArray.Companion.weightedSum],
 * [DoubleDoubleEntangledArray.Companion.cumulativeSet], [DoubleDoubleEntangledArray.Companion.absoluteDiff],
 * [DoubleDoubleEntangledArray.absolute], the window views and serialize/deserialize, and `EMPTY`).
 *
 * The type exposes no element accessor, so result content is verified with the public-API identity
 * `A == B  <=>  weightedSum([A to 1, B to -1])` is empty (equal arrays cancel level-by-level and the
 * zero results are dropped). Empty results are always the [DoubleDoubleEntangledArray.EMPTY] singleton.
 * Quantities and multipliers are integer-valued doubles so the cancellation is bit-exact; levels are
 * dyadic rationals (exactly representable) and are compared, not summed, so they too stay exact.
 */
class DoubleDoubleEntangledArrayTest {

    private val EMPTY = DoubleDoubleEntangledArray.EMPTY

    private fun ddea(vararg pairs: Pair<Double, Double>, allowZero: Boolean = false) =
        DoubleDoubleEntangledArray(pairs.toList(), allowZero)

    private fun ws(vararg terms: Pair<DoubleDoubleEntangledArray, Double>) =
        DoubleDoubleEntangledArray.weightedSum(terms.toList())

    private fun assertEmpty(actual: DoubleDoubleEntangledArray) {
        Assertions.assertSame(EMPTY, actual)
        Assertions.assertEquals(0, actual.size)
    }

    /** Asserts [actual] holds exactly [expected] (canonical: ascending levels, no zeros, no dup levels). */
    private fun assertContent(expected: List<Pair<Double, Double>>, actual: DoubleDoubleEntangledArray) {
        val exp = expected.sortedBy { it.first }
        require(exp.map { it.first }.toSet().size == exp.size) { "expected has duplicate levels" }
        require(exp.none { it.second == 0.0 }) { "expected has a zero quantity" }

        Assertions.assertEquals(exp.size, actual.size, "size")
        if (exp.isEmpty()) { assertEmpty(actual); return }

        Assertions.assertEquals(exp.first().first, actual.firstLevel, "firstLevel")
        Assertions.assertEquals(exp.last().first, actual.lastLevel, "lastLevel")

        // content check: actual - expected must vanish entirely
        val expectedArray = DoubleDoubleEntangledArray(exp, allowZero = false)
        val diff = ws(actual to 1.0, expectedArray to -1.0)
        Assertions.assertSame(EMPTY, diff, "content differs: actual - expected is not empty")
    }

    private fun assertContent(expected: List<Pair<Double, Double>>, vararg terms: Pair<DoubleDoubleEntangledArray, Double>) =
        assertContent(expected, ws(*terms))

    // =====================================================================================
    // constructor(pairs, allowZero)
    // =====================================================================================

    @Test
    fun `ctor empty collection is EMPTY-like`() {
        val a = ddea()
        Assertions.assertEquals(0, a.size)
    }

    @Test
    fun `ctor single element`() {
        val a = ddea(5.0 to 7.0)
        Assertions.assertEquals(1, a.size); Assertions.assertEquals(5.0, a.firstLevel); Assertions.assertEquals(5.0, a.lastLevel)
        assertContent(listOf(5.0 to 7.0), ws(a to 1.0))
    }

    @Test
    fun `ctor already ascending keeps order and endpoints`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        Assertions.assertEquals(3, a.size); Assertions.assertEquals(1.0, a.firstLevel); Assertions.assertEquals(3.0, a.lastLevel)
    }

    @Test
    fun `ctor unsorted input is sorted ascending`() {
        val a = ddea(3.0 to 30.0, 1.0 to 10.0, 2.0 to 20.0)
        Assertions.assertEquals(1.0, a.firstLevel); Assertions.assertEquals(3.0, a.lastLevel)
        assertContent(listOf(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0), ws(a to 1.0))
    }

    @Test
    fun `ctor reverse sorted input is sorted ascending`() {
        val a = ddea(9.0 to 9.0, 7.0 to 7.0, 5.0 to 5.0, 3.0 to 3.0, 1.0 to 1.0)
        assertContent(listOf(1.0 to 1.0, 3.0 to 3.0, 5.0 to 5.0, 7.0 to 7.0, 9.0 to 9.0), ws(a to 1.0))
    }

    @Test
    fun `ctor fractional levels are sorted ascending`() {
        val a = ddea(2.5 to 30.0, 0.5 to 10.0, 1.5 to 20.0, -0.25 to 5.0)
        Assertions.assertEquals(-0.25, a.firstLevel); Assertions.assertEquals(2.5, a.lastLevel)
        assertContent(listOf(-0.25 to 5.0, 0.5 to 10.0, 1.5 to 20.0, 2.5 to 30.0), ws(a to 1.0))
    }

    @Test
    fun `ctor duplicate levels are fused by summing quantities`() {
        val a = ddea(1.0 to 2.0, 1.0 to 3.0, 2.0 to 5.0)
        Assertions.assertEquals(2, a.size)
        assertContent(listOf(1.0 to 5.0, 2.0 to 5.0), ws(a to 1.0))
    }

    @Test
    fun `ctor duplicate fractional levels are fused`() {
        val a = ddea(0.5 to 2.0, 0.5 to 3.0, 1.5 to 5.0)
        Assertions.assertEquals(2, a.size)
        assertContent(listOf(0.5 to 5.0, 1.5 to 5.0), ws(a to 1.0))
    }

    @Test
    fun `ctor duplicate levels fusing to zero are dropped when allowZero false`() {
        val a = ddea(1.0 to 2.0, 1.0 to -2.0, 2.0 to 5.0, allowZero = false)
        Assertions.assertEquals(1, a.size); Assertions.assertEquals(2.0, a.firstLevel)
        assertContent(listOf(2.0 to 5.0), ws(a to 1.0))
    }

    @Test
    fun `ctor duplicate levels fusing to zero are kept when allowZero true`() {
        val a = ddea(1.0 to 2.0, 1.0 to -2.0, 2.0 to 5.0, allowZero = true)
        Assertions.assertEquals(2, a.size); Assertions.assertEquals(1.0, a.firstLevel); Assertions.assertEquals(2.0, a.lastLevel)
    }

    @Test
    fun `ctor zeros dropped when allowZero false`() {
        val a = ddea(1.0 to 0.0, 2.0 to 5.0, 3.0 to 0.0, allowZero = false)
        Assertions.assertEquals(1, a.size); Assertions.assertEquals(2.0, a.firstLevel); Assertions.assertEquals(2.0, a.lastLevel)
    }

    @Test
    fun `ctor zeros kept when allowZero true`() {
        val a = ddea(1.0 to 0.0, 2.0 to 5.0, 3.0 to 0.0, allowZero = true)
        Assertions.assertEquals(3, a.size); Assertions.assertEquals(1.0, a.firstLevel); Assertions.assertEquals(3.0, a.lastLevel)
    }

    @Test
    fun `ctor all zeros with allowZero false is empty`() {
        val a = ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = false)
        Assertions.assertEquals(0, a.size)
    }

    @Test
    fun `ctor negative levels`() {
        val a = ddea(-5.0 to 1.0, -1.0 to 2.0, -3.0 to 3.0)
        Assertions.assertEquals(-5.0, a.firstLevel); Assertions.assertEquals(-1.0, a.lastLevel)
        assertContent(listOf(-5.0 to 1.0, -3.0 to 3.0, -1.0 to 2.0), ws(a to 1.0))
    }

    @Test
    fun `ctor many duplicates triggering compaction branch`() {
        // 100 entries all on 5 levels -> 95 fused; fused*10 > n triggers the copyOf compaction path.
        val pairs = (0 until 100).map { (it % 5).toDouble() to 1.0 }
        val a = DoubleDoubleEntangledArray(pairs, allowZero = false)
        Assertions.assertEquals(5, a.size); Assertions.assertEquals(0.0, a.firstLevel); Assertions.assertEquals(4.0, a.lastLevel)
        assertContent((0 until 5).map { it.toDouble() to 20.0 }, ws(a to 1.0))
    }

    @Test
    fun `ctor large sorted run`() {
        val a = DoubleDoubleEntangledArray((0 until 1000).map { it.toDouble() to (it + 1).toDouble() }, allowZero = false)
        Assertions.assertEquals(1000, a.size); Assertions.assertEquals(0.0, a.firstLevel); Assertions.assertEquals(999.0, a.lastLevel)
    }

    @Test
    fun `ctor large unsorted run triggers heapsort`() {
        // Reverse + shuffle-ish large input forces the heapsort branch (beyond INSERTION_THRESHOLD, not monotone).
        val rnd = Random(7)
        val pairs = (0 until 500).map { it.toDouble() + 0.5 to (it + 1).toDouble() }.shuffled(rnd)
        val a = DoubleDoubleEntangledArray(pairs, allowZero = false)
        Assertions.assertEquals(500, a.size); Assertions.assertEquals(0.5, a.firstLevel); Assertions.assertEquals(499.5, a.lastLevel)
        assertContent((0 until 500).map { it.toDouble() + 0.5 to (it + 1).toDouble() }, ws(a to 1.0))
    }

    // =====================================================================================
    // times
    // =====================================================================================

    @Test
    fun `times by zero returns EMPTY singleton`() {
        assertEmpty(ddea(1.0 to 2.0, 2.0 to 3.0).times(0.0))
    }

    @Test
    fun `times by one returns same instance`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0)
        Assertions.assertSame(a, a.times(1.0))
    }

    @Test
    fun `times on empty returns EMPTY`() {
        assertEmpty(EMPTY.times(2.0))
    }

    @Test
    fun `times scales quantities`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0)
        val scaled = a.times(2.0)
        Assertions.assertEquals(a.size, scaled.size)
        Assertions.assertSame(EMPTY, ws(scaled to 1.0, a to -2.0))
    }

    @Test
    fun `times by negative`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0)
        Assertions.assertSame(EMPTY, ws(a.times(-1.5) to 1.0, a to 1.5))
    }

    @Test
    fun `times keeps zero entries (does not drop)`() {
        val a = ddea(1.0 to 0.0, 2.0 to 5.0, 3.0 to 0.0, allowZero = true)
        val scaled = a.times(2.0)
        Assertions.assertEquals(3, scaled.size); Assertions.assertEquals(1.0, scaled.firstLevel); Assertions.assertEquals(3.0, scaled.lastLevel)
    }

    @Test
    fun `times by fraction`() {
        val a = ddea(1.0 to 4.0, 2.0 to 6.0)
        assertContent(listOf(1.0 to 2.0, 2.0 to 3.0), ws(a.times(0.5) to 1.0))
    }

    @Test
    fun `times preserves levels`() {
        val a = ddea(-2.5 to 1.0, 7.5 to 2.0)
        val scaled = a.times(3.0)
        Assertions.assertEquals(-2.5, scaled.firstLevel); Assertions.assertEquals(7.5, scaled.lastLevel)
    }

    // =====================================================================================
    // div
    // =====================================================================================

    @Test
    fun `div by one returns same instance`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0)
        Assertions.assertSame(a, a.div(1.0))
    }

    @Test
    fun `div on empty returns EMPTY`() {
        assertEmpty(EMPTY.div(2.0))
    }

    @Test
    fun `div scales quantities`() {
        val a = ddea(2.0 to 8.0, 4.0 to 16.0)
        assertContent(listOf(2.0 to 4.0, 4.0 to 8.0), ws(a.div(2.0) to 1.0))
    }

    @Test
    fun `div by negative`() {
        val a = ddea(1.0 to 6.0, 2.0 to 9.0)
        assertContent(listOf(1.0 to -2.0, 2.0 to -3.0), ws(a.div(-3.0) to 1.0))
    }

    @Test
    fun `div keeps zero entries`() {
        val a = ddea(1.0 to 0.0, 2.0 to 4.0, allowZero = true)
        val divided = a.div(2.0)
        Assertions.assertEquals(2, divided.size); Assertions.assertEquals(1.0, divided.firstLevel)
    }

    @Test
    fun `times then div round trips`() {
        val a = ddea(1.0 to 3.0, 5.0 to 7.0, 9.0 to 11.0)
        val round = a.times(4.0).div(4.0)
        Assertions.assertSame(EMPTY, ws(round to 1.0, a to -1.0))
    }

    // =====================================================================================
    // size / firstLevel / lastLevel / EMPTY
    // =====================================================================================

    @Test
    fun `EMPTY has size zero`() {
        Assertions.assertEquals(0, EMPTY.size)
    }

    @Test
    fun `EMPTY firstLevel throws`() {
        Assertions.assertThrows(IndexOutOfBoundsException::class.java) { EMPTY.firstLevel }
    }

    @Test
    fun `EMPTY lastLevel throws`() {
        Assertions.assertThrows(IndexOutOfBoundsException::class.java) { EMPTY.lastLevel }
    }

    @Test
    fun `single element first equals last`() {
        val a = ddea(42.5 to 1.0)
        Assertions.assertEquals(42.5, a.firstLevel); Assertions.assertEquals(42.5, a.lastLevel)
    }

    @Test
    fun `firstLevel and lastLevel reflect the extremes`() {
        val a = ddea(10.0 to 1.0, 20.0 to 1.0, 30.0 to 1.0)
        Assertions.assertEquals(10.0, a.firstLevel); Assertions.assertEquals(30.0, a.lastLevel)
    }

    @Test
    fun `empty constructed array firstLevel throws`() {
        val a = ddea()
        Assertions.assertThrows(IndexOutOfBoundsException::class.java) { a.firstLevel }
    }

    // =====================================================================================
    // weightedSum
    // =====================================================================================

    // ---- degenerate / filtering (n == 0) ------------------------------------------------

    @Test
    fun `ws 01 empty term list is EMPTY`() { assertEmpty(ws()) }

    @Test
    fun `ws 02 single term with zero multiplier is EMPTY`() {
        assertEmpty(ws(ddea(1.0 to 2.0) to 0.0))
    }

    @Test
    fun `ws 03 all terms zero multiplier is EMPTY`() {
        assertEmpty(ws(ddea(1.0 to 2.0) to 0.0, ddea(2.0 to 3.0) to 0.0, ddea(3.0 to 4.0) to 0.0))
    }

    @Test
    fun `ws 04 single empty array is EMPTY`() {
        assertEmpty(ws(EMPTY to 2.0))
    }

    @Test
    fun `ws 05 all empty arrays is EMPTY`() {
        assertEmpty(ws(EMPTY to 1.0, EMPTY to 2.0, EMPTY to 3.0))
    }

    @Test
    fun `ws 06 mix of empty and zero-multiplier is EMPTY`() {
        assertEmpty(ws(EMPTY to 5.0, ddea(1.0 to 1.0) to 0.0))
    }

    // ---- n == 1 (scale path) ------------------------------------------------------------

    @Test
    fun `ws 07 one array doubled`() {
        assertContent(listOf(1.0 to 4.0, 2.0 to 6.0, 3.0 to 8.0), ws(ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0) to 2.0))
    }

    @Test
    fun `ws 08 one array multiplier one is unchanged content`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0)
        assertContent(listOf(1.0 to 2.0, 2.0 to 3.0), ws(a to 1.0))
    }

    @Test
    fun `ws 09 one array negated`() {
        assertContent(listOf(1.0 to -2.0, 5.0 to -3.0), ws(ddea(1.0 to 2.0, 5.0 to 3.0) to 1.0).times(-1.0))
    }

    @Test
    fun `ws 10 one array with zeros drops them`() {
        val a = ddea(1.0 to 2.0, 2.0 to 0.0, 3.0 to 4.0, 4.0 to 0.0, 5.0 to 5.0, allowZero = true)
        assertContent(listOf(1.0 to 6.0, 3.0 to 12.0, 5.0 to 15.0), ws(a to 3.0))
    }

    @Test
    fun `ws 11 one all-zero array is EMPTY`() {
        assertEmpty(ws(ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = true) to 4.0))
    }

    @Test
    fun `ws 12 one single element scaled`() {
        assertContent(listOf(7.0 to 5.0), ws(ddea(7.0 to 2.5) to 2.0))
    }

    @Test
    fun `ws 13 one array negative levels scaled`() {
        assertContent(listOf(-9.0 to -8.0, -4.0 to -6.0, -1.0 to -2.0),
            ws(ddea(-9.0 to 4.0, -4.0 to 3.0, -1.0 to 1.0) to -2.0))
    }

    @Test
    fun `ws 14 one array fractional multiplier`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0), ws(ddea(1.0 to 2.0, 2.0 to 4.0) to 0.5))
    }

    @Test
    fun `ws 15 one large array scaled`() {
        val a = DoubleDoubleEntangledArray((0 until 500).map { it.toDouble() to (it + 1).toDouble() }, allowZero = false)
        val r = ws(a to 2.0)
        Assertions.assertEquals(500, r.size); Assertions.assertEquals(0.0, r.firstLevel); Assertions.assertEquals(499.0, r.lastLevel)
        Assertions.assertSame(EMPTY, ws(r to 1.0, a to -2.0))
    }

    @Test
    fun `ws 16 one real array plus zero-multiplier arrays`() {
        assertContent(listOf(1.0 to 4.0, 2.0 to 6.0),
            ws(ddea(1.0 to 2.0, 2.0 to 3.0) to 2.0, ddea(9.0 to 9.0) to 0.0, ddea(8.0 to 8.0) to 0.0))
    }

    @Test
    fun `ws 17 one real array plus empty arrays`() {
        assertContent(listOf(1.0 to 2.0, 2.0 to 3.0), ws(ddea(1.0 to 2.0, 2.0 to 3.0) to 1.0, EMPTY to 5.0))
    }

    // ---- n == 2 (merge) -----------------------------------------------------------------

    @Test
    fun `ws 18 two fully overlapping`() {
        assertContent(listOf(1.0 to 7.0, 2.0 to 9.0, 3.0 to 11.0),
            ws(ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0) to 2.0, ddea(1.0 to 1.0, 2.0 to 1.0, 3.0 to 1.0) to 3.0))
    }

    @Test
    fun `ws 19 two partial overlap`() {
        assertContent(listOf(1.0 to 4.0, 2.0 to 11.0, 3.0 to 4.0),
            ws(ddea(1.0 to 2.0, 2.0 to 3.0) to 2.0, ddea(2.0 to 5.0, 3.0 to 4.0) to 1.0))
    }

    @Test
    fun `ws 20 two disjoint adjacent`() {
        assertContent(listOf(1.0 to 2.0, 2.0 to 4.0, 3.0 to 9.0, 4.0 to 12.0),
            ws(ddea(1.0 to 1.0, 2.0 to 2.0) to 2.0, ddea(3.0 to 3.0, 4.0 to 4.0) to 3.0))
    }

    @Test
    fun `ws 21 two full cancellation is EMPTY`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0)
        assertEmpty(ws(a to 1.0, a to -1.0))
    }

    @Test
    fun `ws 22 two partial cancellation drops cancelled levels`() {
        assertContent(listOf(2.0 to 2.0),
            ws(ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0) to 1.0, ddea(1.0 to 2.0, 2.0 to 1.0, 3.0 to 4.0) to -1.0))
    }

    @Test
    fun `ws 23 two spread disjoint`() {
        assertContent(listOf(10.0 to 4.0, 500.0 to 5.0, 1000.0 to 6.0, 100000.0 to 4.0),
            ws(ddea(10.0 to 2.0, 1000.0 to 3.0) to 2.0, ddea(500.0 to 5.0, 100000.0 to 4.0) to 1.0))
    }

    @Test
    fun `ws 24 two spread overlap sums shared`() {
        assertContent(listOf(10.0 to 5.0, 5000.0 to 6.0, 999999.0 to 4.0),
            ws(ddea(10.0 to 2.0, 5000.0 to 3.0) to 2.0, ddea(10.0 to 1.0, 999999.0 to 4.0) to 1.0))
    }

    @Test
    fun `ws 25 two fractional shared levels`() {
        assertContent(listOf(0.5 to 5.0, 1.5 to 6.0, 2.5 to 4.0),
            ws(ddea(0.5 to 2.0, 1.5 to 3.0) to 2.0, ddea(0.5 to 1.0, 2.5 to 4.0) to 1.0))
    }

    @Test
    fun `ws 26 two single element same level`() {
        assertContent(listOf(7.0 to 9.0), ws(ddea(7.0 to 2.0) to 2.0, ddea(7.0 to 5.0) to 1.0))
    }

    @Test
    fun `ws 27 two single element different levels`() {
        assertContent(listOf(3.0 to 2.0, 8.0 to 5.0), ws(ddea(3.0 to 1.0) to 2.0, ddea(8.0 to 5.0) to 1.0))
    }

    @Test
    fun `ws 28 two both negative multipliers`() {
        assertContent(listOf(1.0 to -5.0, 2.0 to -8.0),
            ws(ddea(1.0 to 1.0, 2.0 to 2.0) to -1.0, ddea(1.0 to 2.0, 2.0 to 3.0) to -2.0))
    }

    @Test
    fun `ws 29 two large contiguous`() {
        val a = DoubleDoubleEntangledArray((0 until 400).map { it.toDouble() to 2.0 }, allowZero = false)
        val b = DoubleDoubleEntangledArray((0 until 400).map { it.toDouble() to 3.0 }, allowZero = false)
        val r = ws(a to 1.0, b to 1.0)
        Assertions.assertEquals(400, r.size)
        assertContent((0 until 400).map { it.toDouble() to 5.0 }, r)
    }

    @Test
    fun `ws 30 two heavy overlap contiguous`() {
        val a = DoubleDoubleEntangledArray((0 until 100).map { it.toDouble() to 1.0 }, allowZero = false)
        val b = DoubleDoubleEntangledArray((50 until 150).map { it.toDouble() to 1.0 }, allowZero = false)
        val expected = (0 until 150).map { it.toDouble() to (if (it in 50 until 100) 2.0 else 1.0) }
        assertContent(expected, ws(a to 1.0, b to 1.0))
    }

    @Test
    fun `ws 31 two arrays where b strictly above a`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 100.0 to 30.0, 200.0 to 40.0),
            ws(ddea(1.0 to 1.0, 2.0 to 2.0) to 1.0, ddea(100.0 to 3.0, 200.0 to 4.0) to 10.0))
    }

    // ---- n >= 3 (mergeN) ----------------------------------------------------------------

    @Test
    fun `ws 32 three fully overlapping`() {
        assertContent(listOf(1.0 to 8.0, 2.0 to 11.0),
            ws(ddea(1.0 to 1.0, 2.0 to 2.0) to 1.0, ddea(1.0 to 2.0, 2.0 to 3.0) to 2.0, ddea(1.0 to 1.0, 2.0 to 1.0) to 3.0))
    }

    @Test
    fun `ws 33 three chained partial overlap`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 2.0, 4.0 to 1.0),
            ws(ddea(1.0 to 1.0, 2.0 to 1.0) to 1.0, ddea(2.0 to 1.0, 3.0 to 1.0) to 1.0, ddea(3.0 to 1.0, 4.0 to 1.0) to 1.0))
    }

    @Test
    fun `ws 34 three disjoint`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0),
            ws(ddea(1.0 to 1.0) to 1.0, ddea(2.0 to 2.0) to 1.0, ddea(3.0 to 3.0) to 1.0))
    }

    @Test
    fun `ws 35 three full cancellation is EMPTY`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0)
        assertEmpty(ws(a to 1.0, a to 1.0, a to -2.0))
    }

    @Test
    fun `ws 36 three spread`() {
        assertContent(listOf(10.0 to 5.0, 500.0 to 1.0, 9000.0 to 1.0, 100000.0 to 5.0),
            ws(ddea(10.0 to 1.0, 100000.0 to 2.0) to 1.0, ddea(500.0 to 1.0, 100000.0 to 3.0) to 1.0, ddea(10.0 to 4.0, 9000.0 to 1.0) to 1.0))
    }

    @Test
    fun `ws 37 five arrays`() {
        val arrs = (0 until 5).map { ddea(it.toDouble() to 1.0, (it + 1).toDouble() to 1.0) }
        assertContent(listOf(0.0 to 1.0, 1.0 to 2.0, 2.0 to 2.0, 3.0 to 2.0, 4.0 to 2.0, 5.0 to 1.0),
            DoubleDoubleEntangledArray.weightedSum(arrs.map { it to 1.0 }))
    }

    @Test
    fun `ws 38 ten identical arrays equal times ten`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        val sum = DoubleDoubleEntangledArray.weightedSum(List(10) { a to 1.0 })
        assertContent(listOf(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0), sum)
    }

    @Test
    fun `ws 39 effective three after filtering empty and zero-multiplier padding`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 2.0, 4.0 to 1.0),
            ws(ddea(1.0 to 1.0, 2.0 to 1.0) to 1.0, ddea(9.0 to 9.0) to 0.0, ddea(2.0 to 1.0, 3.0 to 1.0) to 1.0,
               EMPTY to 5.0, ddea(3.0 to 1.0, 4.0 to 1.0) to 1.0))
    }

    @Test
    fun `ws 40 three mixed sign multipliers`() {
        assertContent(listOf(1.0 to 4.0, 2.0 to 1.0, 3.0 to -3.0),
            ws(ddea(1.0 to 2.0, 2.0 to 2.0, 3.0 to 1.0) to 2.0, ddea(2.0 to 3.0) to -1.0, ddea(3.0 to 5.0) to -1.0))
    }

    @Test
    fun `ws 41 four spread arrays`() {
        assertContent(listOf(1.0 to 1.0, 100.0 to 1.0, 10000.0 to 1.0, 1000000.0 to 1.0),
            ws(ddea(1.0 to 1.0) to 1.0, ddea(100.0 to 1.0) to 1.0, ddea(10000.0 to 1.0) to 1.0, ddea(1000000.0 to 1.0) to 1.0))
    }

    @Test
    fun `ws 42 three fractional levels`() {
        assertContent(listOf(0.25 to 2.0, 0.5 to 3.0, 0.75 to 5.0),
            ws(ddea(0.25 to 1.0, 0.5 to 1.0) to 2.0, ddea(0.5 to 1.0, 0.75 to 1.0) to 1.0, ddea(0.75 to 4.0) to 1.0))
    }

    // ---- algebraic properties -----------------------------------------------------------

    @Test
    fun `ws 43 commutative in term order`() {
        val a = ddea(1.0 to 2.0, 3.0 to 4.0); val b = ddea(2.0 to 5.0, 3.0 to 6.0)
        val ab = ws(a to 2.0, b to 3.0); val ba = ws(b to 3.0, a to 2.0)
        Assertions.assertSame(EMPTY, ws(ab to 1.0, ba to -1.0)); Assertions.assertEquals(ab.size, ba.size)
    }

    @Test
    fun `ws 44 single term equals scalar times`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0)
        Assertions.assertSame(EMPTY, ws(ws(a to 3.0) to 1.0, a.times(3.0) to -1.0))
    }

    @Test
    fun `ws 45 self sum equals times two`() {
        val a = ddea(1.0 to 7.0, 4.0 to 9.0)
        Assertions.assertSame(EMPTY, ws(ws(a to 1.0, a to 1.0) to 1.0, a.times(2.0) to -1.0))
    }

    @Test
    fun `ws 46 linearity of multiplier`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0); val b = ddea(2.0 to 4.0, 3.0 to 5.0)
        val scaledSum = ws(a to 2.0, b to 2.0)
        val sumScaled = ws(a to 1.0, b to 1.0).times(2.0)
        Assertions.assertSame(EMPTY, ws(scaledSum to 1.0, sumScaled to -1.0))
    }

    @Test
    fun `ws 47 associativity with unit multipliers`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0); val b = ddea(2.0 to 3.0, 3.0 to 4.0); val c = ddea(3.0 to 5.0, 4.0 to 6.0)
        val all = ws(a to 1.0, b to 1.0, c to 1.0)
        val nested = ws(ws(a to 1.0, b to 1.0) to 1.0, c to 1.0)
        Assertions.assertSame(EMPTY, ws(all to 1.0, nested to -1.0)); Assertions.assertEquals(all.size, nested.size)
    }

    @Test
    fun `ws 48 adding empty leaves content unchanged`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0)
        Assertions.assertSame(EMPTY, ws(ws(a to 1.0, EMPTY to 7.0) to 1.0, a to -1.0))
    }

    // ---- edge / robustness ---------------------------------------------------------------

    @Test
    fun `ws 49 very large and very small levels`() {
        assertContent(listOf(-1.0e18 to 2.0, 1.0e18 to 3.0),
            ws(ddea(-1.0e18 to 1.0, 1.0e18 to 1.0) to 1.0,
               ddea(-1.0e18 to 1.0, 1.0e18 to 2.0) to 1.0))
    }

    @Test
    fun `ws 50 result of weightedSum can be summed again`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0); val b = ddea(2.0 to 3.0, 3.0 to 4.0)
        val first = ws(a to 1.0, b to 1.0)
        assertContent(listOf(1.0 to 1.0, 2.0 to 8.0, 3.0 to 8.0), ws(first to 1.0, b to 1.0))
    }

    @Test
    fun `ws 51 result endpoints`() {
        val r = ws(ddea(5.0 to 1.0, 6.0 to 1.0, 7.0 to 1.0) to 1.0, ddea(6.0 to 1.0, 7.0 to 1.0, 8.0 to 1.0) to 1.0)
        Assertions.assertEquals(5.0, r.firstLevel); Assertions.assertEquals(8.0, r.lastLevel); Assertions.assertEquals(4, r.size)
    }

    // ---- randomized cross-checks --------------------------------------------------------

    @Test
    fun `ws 52 randomized two-array matches reference`() {
        val rnd = Random(1)
        repeat(400) {
            val spread = rnd.nextBoolean()
            val a = randomPairs(rnd, spread); val b = randomPairs(rnd, spread)
            val mulA = rnd.nextInt(-3, 4).toDouble(); val mulB = rnd.nextInt(-3, 4).toDouble()
            val actual = ws(build(a) to mulA, build(b) to mulB)
            assertContent(reference(listOf(a to mulA, b to mulB)), actual)
        }
    }

    @Test
    fun `ws 53 randomized N-array matches reference`() {
        val rnd = Random(2)
        repeat(400) {
            val spread = rnd.nextInt(3) == 0
            val n = rnd.nextInt(3, 7)
            val pairsAndMul = (0 until n).map { randomPairs(rnd, spread) to rnd.nextInt(-3, 4).toDouble() }
            val terms = pairsAndMul.map { build(it.first) to it.second }
            assertContent(reference(pairsAndMul), DoubleDoubleEntangledArray.weightedSum(terms))
        }
    }

    @Test
    fun `ws 54 randomized fractional levels matches reference`() {
        val rnd = Random(3)
        repeat(400) {
            val n = rnd.nextInt(2, 6)
            val pairsAndMul = (0 until n).map { fractionalPairs(rnd) to rnd.nextInt(-2, 3).toDouble() }
            val terms = pairsAndMul.map { build(it.first) to it.second }
            assertContent(reference(pairsAndMul), DoubleDoubleEntangledArray.weightedSum(terms))
        }
    }

    /** Builds a [DoubleDoubleEntangledArray] from `pairs` (allowing zeros so the merge zero-drop is exercised). */
    private fun build(pairs: List<Pair<Double, Double>>) = DoubleDoubleEntangledArray(pairs, allowZero = true)

    /** Dyadic-rational steps: exactly representable, so running sums stay bit-exact. */
    private val steps = doubleArrayOf(0.5, 1.0, 1.5, 2.0, 3.0)

    /** Random ascending pairs with integer quantities (some zero), keeping reference sums bit-exact. */
    private fun randomPairs(rnd: Random, spread: Boolean, mult: Double = if (spread) 30.0 else 1.0): List<Pair<Double, Double>> {
        val size = rnd.nextInt(0, 20)
        var level = rnd.nextInt(-5, 5).toDouble()
        val pairs = ArrayList<Pair<Double, Double>>(size)
        for (k in 0 until size) {
            level += steps[rnd.nextInt(steps.size)] * mult
            val q = if (rnd.nextDouble() < 0.2) 0.0 else rnd.nextInt(-20, 21).toDouble()
            pairs.add(level to q)
        }
        return pairs
    }

    /** Random ascending pairs whose levels are dyadic fractions, to stress non-integer level handling. */
    private fun fractionalPairs(rnd: Random): List<Pair<Double, Double>> {
        val size = rnd.nextInt(1, 15)
        var level = rnd.nextInt(-3, 3) + 0.25 * rnd.nextInt(0, 4)
        val pairs = ArrayList<Pair<Double, Double>>(size)
        for (k in 0 until size) {
            level += 0.25 * (1 + rnd.nextInt(8))
            val q = if (rnd.nextDouble() < 0.2) 0.0 else rnd.nextInt(-20, 21).toDouble()
            pairs.add(level to q)
        }
        return pairs
    }

    /** Reference weighted sum computed directly from the raw input pairs: combine, scale, drop zeros, ascending. */
    private fun reference(terms: List<Pair<List<Pair<Double, Double>>, Double>>): List<Pair<Double, Double>> {
        val acc = sortedMapOf<Double, Double>()
        for ((pairs, mult) in terms) {
            if (mult == 0.0) continue
            for ((lvl, q) in pairs) acc[lvl] = (acc[lvl] ?: 0.0) + q * mult
        }
        return acc.entries.filter { it.value != 0.0 }.map { it.key to it.value }
    }

    // =====================================================================================
    // cumulativeSet  — left-to-right overlay A.set(B).set(C): last writer wins, zeros dropped
    // =====================================================================================

    private fun cs(vararg arrays: DoubleDoubleEntangledArray) =
        DoubleDoubleEntangledArray.cumulativeSet(arrays.toList())

    // ---- degenerate / filtering (n == 0) ------------------------------------------------

    @Test
    fun `cs 01 empty list is EMPTY`() { assertEmpty(cs()) }

    @Test
    fun `cs 02 single empty array is EMPTY`() { assertEmpty(cs(EMPTY)) }

    @Test
    fun `cs 03 all empty arrays is EMPTY`() { assertEmpty(cs(EMPTY, EMPTY, EMPTY)) }

    // ---- n == 1 -------------------------------------------------------------------------

    @Test
    fun `cs 04 single array returns the same instance`() {
        val a = ddea(1.0 to 2.0, 5.0 to 3.0)
        Assertions.assertSame(a, cs(a))
    }

    @Test
    fun `cs 05 single non-empty among empties returns the same instance`() {
        val a = ddea(2.0 to 5.0)
        Assertions.assertSame(a, cs(EMPTY, EMPTY, a, EMPTY))
    }

    @Test
    fun `cs 06 single array content unchanged`() {
        val a = ddea(1.0 to 2.0, 5.0 to 3.0, 9.0 to 4.0)
        assertContent(listOf(1.0 to 2.0, 5.0 to 3.0, 9.0 to 4.0), cs(a))
    }

    @Test
    fun `cs 07 single array with zeros drops them`() {
        assertContent(listOf(2.0 to 5.0), cs(ddea(1.0 to 0.0, 2.0 to 5.0, 3.0 to 0.0, allowZero = true)))
        assertEmpty(cs(ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = true)))
    }

    // ---- n == 2 -------------------------------------------------------------------------

    @Test
    fun `cs 08 two disjoint adjacent levels union`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0, 4.0 to 4.0),
            cs(ddea(1.0 to 1.0, 2.0 to 2.0), ddea(3.0 to 3.0, 4.0 to 4.0)))
    }

    @Test
    fun `cs 09 two full overlap replaces all values`() {
        assertContent(listOf(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0),
            cs(ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0), ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0)))
    }

    @Test
    fun `cs 10 two partial overlap shared replaced unique kept`() {
        assertContent(listOf(1.0 to 10.0, 2.0 to 99.0, 3.0 to 30.0),
            cs(ddea(1.0 to 10.0, 2.0 to 20.0), ddea(2.0 to 99.0, 3.0 to 30.0)))
    }

    @Test
    fun `cs 11 later clears a shared level to zero and it is dropped`() {
        assertContent(listOf(1.0 to 5.0, 3.0 to 7.0),
            cs(ddea(1.0 to 5.0, 2.0 to 6.0, 3.0 to 7.0), ddea(2.0 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 12 earlier zero overwritten by later nonzero`() {
        assertContent(listOf(5.0 to 7.0),
            cs(ddea(5.0 to 0.0, allowZero = true), ddea(5.0 to 7.0)))
    }

    @Test
    fun `cs 13 order matters - later wins`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0)
        val b = ddea(2.0 to 99.0, 3.0 to 30.0)
        assertContent(listOf(1.0 to 10.0, 2.0 to 99.0, 3.0 to 30.0), cs(a, b))
        assertContent(listOf(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0), cs(b, a))
    }

    @Test
    fun `cs 14 idempotent - same array twice equals itself`() {
        val a = ddea(1.0 to 3.0, 5.0 to 7.0, 9.0 to 11.0)
        assertContent(listOf(1.0 to 3.0, 5.0 to 7.0, 9.0 to 11.0), cs(a, a))
    }

    @Test
    fun `cs 15 two singletons same level last wins`() {
        assertContent(listOf(7.0 to 5.0), cs(ddea(7.0 to 2.0), ddea(7.0 to 5.0)))
    }

    @Test
    fun `cs 16 two singletons different levels`() {
        assertContent(listOf(3.0 to 1.0, 8.0 to 2.0), cs(ddea(3.0 to 1.0), ddea(8.0 to 2.0)))
    }

    @Test
    fun `cs 17 negative levels and quantities`() {
        assertContent(listOf(-5.0 to -1.0, -1.0 to -9.0, 3.0 to -4.0),
            cs(ddea(-5.0 to -1.0, -1.0 to -2.0), ddea(-1.0 to -9.0, 3.0 to -4.0)))
    }

    @Test
    fun `cs 18 two all-clear yields EMPTY`() {
        assertEmpty(cs(ddea(1.0 to 5.0, 2.0 to 6.0), ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 19 fractional shared levels last wins`() {
        assertContent(listOf(0.5 to 9.0, 1.5 to 1.0, 2.5 to 9.0),
            cs(ddea(0.5 to 1.0, 1.5 to 1.0, 2.5 to 1.0), ddea(0.5 to 9.0, 2.5 to 9.0)))
    }

    @Test
    fun `cs 20 endpoints reflect union extremes`() {
        val r = cs(ddea(5.0 to 1.0, 6.0 to 1.0), ddea(2.0 to 1.0, 9.0 to 1.0))
        Assertions.assertEquals(2.0, r.firstLevel); Assertions.assertEquals(9.0, r.lastLevel); Assertions.assertEquals(4, r.size)
    }

    @Test
    fun `cs 21 two spread disjoint union`() {
        assertContent(listOf(10.0 to 1.0, 500.0 to 5.0, 1000.0 to 3.0, 100000.0 to 4.0),
            cs(ddea(10.0 to 1.0, 1000.0 to 3.0), ddea(500.0 to 5.0, 100000.0 to 4.0)))
    }

    @Test
    fun `cs 22 two spread overlap later wins`() {
        assertContent(listOf(10.0 to 9.0, 5000.0 to 3.0, 999999.0 to 4.0),
            cs(ddea(10.0 to 2.0, 5000.0 to 3.0), ddea(10.0 to 9.0, 999999.0 to 4.0)))
    }

    @Test
    fun `cs 23 two spread later clears shared`() {
        assertContent(listOf(10.0 to 5.0),
            cs(ddea(10.0 to 5.0, 100000.0 to 7.0), ddea(100000.0 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 24 idempotent on spread array`() {
        val a = ddea(10.0 to 3.0, 100000.0 to 7.0)
        assertContent(listOf(10.0 to 3.0, 100000.0 to 7.0), cs(a, a))
    }

    // ---- n >= 3 -------------------------------------------------------------------------

    @Test
    fun `cs 25 three last writer wins per shared level`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0),
            cs(ddea(1.0 to 1.0, 2.0 to 1.0, 3.0 to 1.0), ddea(2.0 to 2.0, 3.0 to 2.0), ddea(3.0 to 3.0)))
    }

    @Test
    fun `cs 26 three chained partial overlap`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0, 4.0 to 4.0),
            cs(ddea(1.0 to 1.0, 2.0 to 1.0), ddea(2.0 to 2.0, 3.0 to 2.0), ddea(3.0 to 3.0, 4.0 to 4.0)))
    }

    @Test
    fun `cs 27 three disjoint`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0),
            cs(ddea(1.0 to 1.0), ddea(2.0 to 2.0), ddea(3.0 to 3.0)))
    }

    @Test
    fun `cs 28 three - last array clears a level`() {
        assertContent(listOf(1.0 to 5.0, 2.0 to 7.0),
            cs(ddea(1.0 to 5.0, 2.0 to 5.0), ddea(2.0 to 7.0, 3.0 to 7.0), ddea(3.0 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 29 three - clear then reset keeps the last nonzero`() {
        assertContent(listOf(5.0 to 4.0),
            cs(ddea(5.0 to 9.0), ddea(5.0 to 0.0, allowZero = true), ddea(5.0 to 4.0)))
    }

    @Test
    fun `cs 30 three - level in all arrays last is zero is dropped`() {
        assertEmpty(cs(ddea(5.0 to 1.0), ddea(5.0 to 2.0), ddea(5.0 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 31 many identical contiguous arrays equal a single one`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0),
            DoubleDoubleEntangledArray.cumulativeSet(List(8) { a }))
    }

    @Test
    fun `cs 32 three spread disjoint`() {
        assertContent(listOf(10.0 to 1.0, 5000.0 to 2.0, 100000.0 to 3.0),
            cs(ddea(10.0 to 1.0), ddea(5000.0 to 2.0), ddea(100000.0 to 3.0)))
    }

    @Test
    fun `cs 33 three spread overlap last writer`() {
        assertContent(listOf(10.0 to 2.0, 5000.0 to 2.0, 100000.0 to 3.0),
            cs(ddea(10.0 to 1.0, 100000.0 to 1.0), ddea(10.0 to 2.0, 5000.0 to 2.0), ddea(100000.0 to 3.0)))
    }

    @Test
    fun `cs 34 three spread clear all yields EMPTY`() {
        assertEmpty(cs(
            ddea(1.0 to 1.0, 100000.0 to 1.0),
            ddea(1.0 to 2.0, 50000.0 to 2.0),
            ddea(1.0 to 0.0, 50000.0 to 0.0, 100000.0 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 35 four spread arrays union`() {
        assertContent(listOf(1.0 to 1.0, 100.0 to 2.0, 10000.0 to 3.0, 1000000.0 to 4.0),
            cs(ddea(1.0 to 1.0), ddea(100.0 to 2.0), ddea(10000.0 to 3.0), ddea(1000000.0 to 4.0)))
    }

    @Test
    fun `cs 36 many identical spread arrays equal a single one (tie-drain)`() {
        val a = ddea(10.0 to 1.0, 100000.0 to 2.0)
        assertContent(listOf(10.0 to 1.0, 100000.0 to 2.0),
            DoubleDoubleEntangledArray.cumulativeSet(List(6) { a }))
    }

    @Test
    fun `cs 37 ten arrays distinct levels union`() {
        val arrs = (0 until 10).map { ddea(it.toDouble() to (it + 1).toDouble()) }
        assertContent((0 until 10).map { it.toDouble() to (it + 1).toDouble() },
            DoubleDoubleEntangledArray.cumulativeSet(arrs))
    }

    // ---- filtering + compaction (empties between real inputs) ----------------------------

    @Test
    fun `cs 38 interspersed empties spread forces merge with compaction`() {
        val a = ddea(10.0 to 1.0, 100000.0 to 2.0)
        val b = ddea(500.0 to 3.0, 100000.0 to 9.0)
        val c = ddea(10.0 to 7.0, 9000.0 to 5.0)
        assertContent(listOf(10.0 to 7.0, 500.0 to 3.0, 9000.0 to 5.0, 100000.0 to 9.0),
            cs(EMPTY, a, EMPTY, b, EMPTY, c, EMPTY))
    }

    @Test
    fun `cs 39 empties reducing to two dispatches to set2`() {
        assertContent(listOf(1.0 to 2.0, 3.0 to 9.0, 5.0 to 6.0),
            cs(EMPTY, ddea(1.0 to 2.0, 3.0 to 4.0), EMPTY, ddea(3.0 to 9.0, 5.0 to 6.0)))
    }

    @Test
    fun `cs 40 empties reducing to one returns the same instance`() {
        val a = ddea(2.0 to 5.0, 7.0 to 8.0)
        Assertions.assertSame(a, cs(EMPTY, EMPTY, a, EMPTY))
    }

    // ---- larger / re-use / properties ---------------------------------------------------

    @Test
    fun `cs 41 large contiguous overlay - last wins`() {
        val a = DoubleDoubleEntangledArray((0 until 400).map { it.toDouble() to 1.0 }, allowZero = false)
        val b = DoubleDoubleEntangledArray((0 until 400).map { it.toDouble() to 2.0 }, allowZero = false)
        assertContent((0 until 400).map { it.toDouble() to 2.0 }, cs(a, b))
    }

    @Test
    fun `cs 42 large spread overlay - last wins`() {
        val a = DoubleDoubleEntangledArray((0 until 300).map { (it * 1000).toDouble() to 1.0 }, allowZero = false)
        val b = DoubleDoubleEntangledArray((0 until 300).map { (it * 1000).toDouble() to 2.0 }, allowZero = false)
        assertContent((0 until 300).map { (it * 1000).toDouble() to 2.0 }, cs(a, b))
    }

    @Test
    fun `cs 43 result can be overlaid again`() {
        val first = cs(ddea(1.0 to 1.0, 2.0 to 2.0), ddea(2.0 to 5.0, 3.0 to 3.0)) // {1:1, 2:5, 3:3}
        assertContent(listOf(1.0 to 1.0, 2.0 to 5.0, 3.0 to 9.0, 4.0 to 4.0),
            cs(first, ddea(3.0 to 9.0, 4.0 to 4.0)))
    }

    @Test
    fun `cs 44 equals left-deep pairwise fold A set B set C`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        val b = ddea(2.0 to 9.0, 4.0 to 4.0)
        val c = ddea(3.0 to 7.0, 4.0 to 8.0, 5.0 to 5.0)
        val all = cs(a, b, c)
        val fold = cs(cs(a, b), c)
        Assertions.assertSame(EMPTY, ws(all to 1.0, fold to -1.0))
        Assertions.assertEquals(all.size, fold.size)
    }

    @Test
    fun `cs 45 not commutative in input order`() {
        val a = ddea(1.0 to 1.0, 5.0 to 1.0)
        val b = ddea(5.0 to 2.0)
        val c = ddea(5.0 to 3.0)
        assertContent(listOf(1.0 to 1.0, 5.0 to 3.0), cs(a, b, c))
        assertContent(listOf(1.0 to 1.0, 5.0 to 1.0), cs(b, c, a))
    }

    // ---- randomized cross-checks against a direct overlay reference ----------------------

    @Test
    fun `cs 46 randomized two-array matches reference`() {
        val rnd = Random(11)
        repeat(400) {
            val spread = rnd.nextBoolean()
            val a = setPairs(rnd, spread); val b = setPairs(rnd, spread)
            assertContent(referenceSet(listOf(a, b)), cs(build(a), build(b)))
        }
    }

    @Test
    fun `cs 47 randomized N-array matches reference`() {
        val rnd = Random(12)
        repeat(400) {
            val spread = rnd.nextBoolean()
            val n = rnd.nextInt(3, 8)
            val arrays = (0 until n).map { setPairs(rnd, spread) }
            assertContent(referenceSet(arrays),
                DoubleDoubleEntangledArray.cumulativeSet(arrays.map { build(it) }))
        }
    }

    @Test
    fun `cs 48 randomized interspersed empties matches reference`() {
        val rnd = Random(14)
        repeat(400) {
            val spread = rnd.nextBoolean()
            val n = rnd.nextInt(2, 7)
            val arrays = (0 until n).map { setPairs(rnd, spread) }
            val terms = ArrayList<DoubleDoubleEntangledArray>()
            for (a in arrays) { terms.add(EMPTY); terms.add(build(a)) }
            terms.add(EMPTY)
            assertContent(referenceSet(arrays), DoubleDoubleEntangledArray.cumulativeSet(terms))
        }
    }

    @Test
    fun `cs 49 randomized fractional levels matches reference`() {
        val rnd = Random(15)
        repeat(400) {
            val n = rnd.nextInt(2, 6)
            val arrays = (0 until n).map { fractionalPairs(rnd).distinctBy { it.first } }
            assertContent(referenceSet(arrays),
                DoubleDoubleEntangledArray.cumulativeSet(arrays.map { build(it) }))
        }
    }

    @Test
    fun `cs 50 randomized inputs riddled with zeros stay canonical`() {
        val rnd = Random(99)
        repeat(400) {
            val spread = rnd.nextBoolean()
            val n = rnd.nextInt(1, 7)
            val arrays = (0 until n).map { zeroHeavyPairs(rnd, spread) }
            val result = DoubleDoubleEntangledArray.cumulativeSet(arrays.map { build(it) })
            assertContent(referenceSet(arrays), result)
        }
    }

    /** Like [setPairs] but ~half the quantities are zero, to stress the zero-drop on every path. */
    private fun zeroHeavyPairs(rnd: Random, spread: Boolean): List<Pair<Double, Double>> {
        val mult = if (spread) 40.0 else 1.0
        val size = rnd.nextInt(1, 20)
        var level = rnd.nextInt(-5, 5).toDouble()
        val pairs = ArrayList<Pair<Double, Double>>(size)
        for (k in 0 until size) {
            level += steps[rnd.nextInt(steps.size)] * mult
            val q = if (rnd.nextDouble() < 0.5) 0.0 else rnd.nextInt(-20, 21).toDouble()
            pairs.add(level to q)
        }
        return pairs
    }

    /** Random ascending pairs with unique levels and at least one entry (some quantities zero). */
    private fun setPairs(rnd: Random, spread: Boolean, mult: Double = if (spread) 40.0 else 1.0): List<Pair<Double, Double>> {
        val size = rnd.nextInt(1, 20)
        var level = rnd.nextInt(-5, 5).toDouble()
        val pairs = ArrayList<Pair<Double, Double>>(size)
        for (k in 0 until size) {
            level += steps[rnd.nextInt(steps.size)] * mult
            val q = if (rnd.nextDouble() < 0.2) 0.0 else rnd.nextInt(-20, 21).toDouble()
            pairs.add(level to q)
        }
        return pairs
    }

    /** Reference overlay: later arrays set each level (last writer wins); zeros dropped; ascending. */
    private fun referenceSet(arrays: List<List<Pair<Double, Double>>>): List<Pair<Double, Double>> {
        val acc = sortedMapOf<Double, Double>()
        for (pairs in arrays) for ((lvl, q) in pairs) acc[lvl] = q
        return acc.entries.filter { it.value != 0.0 }.map { it.key to it.value }
    }

    // =====================================================================================
    // absoluteDiff  — level-by-level |qa - qb|, absent side counts as 0, zeros dropped
    // =====================================================================================

    private fun ad(a: DoubleDoubleEntangledArray, b: DoubleDoubleEntangledArray) =
        DoubleDoubleEntangledArray.absoluteDiff(a, b)

    /** Reference: for every level in either input, |a - b| with an absent side as 0; zeros dropped, ascending. */
    private fun referenceDiff(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val ma = a.toMap(); val mb = b.toMap()
        require(ma.size == a.size && mb.size == b.size) { "reference assumes unique levels per side" }
        val levels = (ma.keys + mb.keys).toSortedSet()
        return levels.mapNotNull { l ->
            val q = abs((ma[l] ?: 0.0) - (mb[l] ?: 0.0))
            if (q != 0.0) l to q else null
        }
    }

    @Test
    fun `ad 01 both empty is EMPTY`() { assertEmpty(ad(EMPTY, EMPTY)) }

    @Test
    fun `ad 02 first empty yields absolute values of second`() {
        assertContent(listOf(1.0 to 2.0, 3.0 to 4.0), ad(EMPTY, ddea(1.0 to 2.0, 3.0 to -4.0)))
    }

    @Test
    fun `ad 03 second empty yields absolute values of first`() {
        assertContent(listOf(1.0 to 2.0, 3.0 to 4.0), ad(ddea(1.0 to -2.0, 3.0 to 4.0), EMPTY))
    }

    @Test
    fun `ad 03b empty against all-positive array returns the same instance`() {
        val a = ddea(1.0 to 2.0, 3.0 to 4.0)
        Assertions.assertSame(a, ad(EMPTY, a))
        Assertions.assertSame(a, ad(a, EMPTY))
    }

    @Test
    fun `ad 03c empty against array with a negative builds a fresh canonical copy`() {
        val a = ddea(1.0 to -2.0, 3.0 to 4.0)
        val r = ad(EMPTY, a)
        Assertions.assertNotSame(a, r)
        assertContent(listOf(1.0 to 2.0, 3.0 to 4.0), r)
    }

    @Test
    fun `ad 03d empty against array with a zero drops it (not the same instance)`() {
        val a = ddea(1.0 to 0.0, 3.0 to 4.0, allowZero = true)
        val r = ad(a, EMPTY)
        Assertions.assertNotSame(a, r)
        assertContent(listOf(3.0 to 4.0), r)
    }

    @Test
    fun `ad 03e empty against all-zero array is EMPTY`() {
        assertEmpty(ad(EMPTY, ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = true)))
    }

    @Test
    fun `ad 04 identical arrays cancel to EMPTY`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0)
        assertEmpty(ad(a, a))
    }

    @Test
    fun `ad 05 full overlap differing values`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0),
            ad(ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0), ddea(1.0 to 9.0, 2.0 to 18.0, 3.0 to 27.0)))
    }

    @Test
    fun `ad 06 disjoint adjacent levels union of absolute values`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0, 4.0 to 4.0),
            ad(ddea(1.0 to 1.0, 2.0 to 2.0), ddea(3.0 to -3.0, 4.0 to -4.0)))
    }

    @Test
    fun `ad 07 partial overlap shared subtracts unique kept as magnitude`() {
        assertContent(listOf(1.0 to 7.0, 2.0 to 3.0, 3.0 to 9.0),
            ad(ddea(1.0 to 7.0, 2.0 to 5.0), ddea(2.0 to 2.0, 3.0 to -9.0)))
    }

    @Test
    fun `ad 08 shared level with equal values is dropped`() {
        assertContent(listOf(1.0 to 1.0, 3.0 to 3.0),
            ad(ddea(1.0 to 1.0, 2.0 to 5.0, 3.0 to 3.0), ddea(2.0 to 5.0)))
    }

    @Test
    fun `ad 09 result is always non-negative`() {
        assertContent(listOf(1.0 to 4.0, 2.0 to 4.0),
            ad(ddea(1.0 to 1.0, 2.0 to 1.0), ddea(1.0 to 5.0, 2.0 to 5.0)))
    }

    @Test
    fun `ad 10 symmetric in argument order`() {
        val a = ddea(1.0 to 2.0, 3.0 to 4.0, 5.0 to 6.0)
        val b = ddea(1.0 to 9.0, 2.0 to 1.0, 5.0 to 6.0)
        val ab = ad(a, b); val ba = ad(b, a)
        Assertions.assertSame(EMPTY, ws(ab to 1.0, ba to -1.0)); Assertions.assertEquals(ab.size, ba.size)
    }

    @Test
    fun `ad 11 fractional levels`() {
        assertContent(listOf(0.5 to 1.0, 1.5 to 4.0, 2.5 to 2.0),
            ad(ddea(0.5 to 1.0, 1.5 to 5.0, 3.5 to 7.0), ddea(1.5 to 1.0, 2.5 to 2.0, 3.5 to 7.0)))
    }

    @Test
    fun `ad 12 endpoints reflect surviving extremes`() {
        val r = ad(ddea(1.0 to 5.0, 4.0 to 1.0, 9.0 to 5.0), ddea(1.0 to 5.0, 4.0 to 8.0, 9.0 to 5.0))
        assertContent(listOf(4.0 to 7.0), r)
        Assertions.assertEquals(4.0, r.firstLevel); Assertions.assertEquals(4.0, r.lastLevel); Assertions.assertEquals(1, r.size)
    }

    @Test
    fun `ad 13 spread disjoint union`() {
        assertContent(listOf(10.0 to 1.0, 500.0 to 5.0, 1000.0 to 3.0, 100000.0 to 4.0),
            ad(ddea(10.0 to 1.0, 1000.0 to 3.0), ddea(500.0 to 5.0, 100000.0 to -4.0)))
    }

    @Test
    fun `ad 14 spread overlap differs and cancels`() {
        assertContent(listOf(10.0 to 7.0, 5000.0 to 3.0),
            ad(ddea(10.0 to 2.0, 5000.0 to 3.0, 999999.0 to 4.0), ddea(10.0 to 9.0, 999999.0 to 4.0)))
    }

    @Test
    fun `ad 15 zero entry against absent side is dropped`() {
        assertContent(listOf(1.0 to 3.0),
            ad(ddea(1.0 to 3.0, 2.0 to 0.0, allowZero = true), EMPTY))
    }

    @Test
    fun `ad 16 zero against nonzero yields the magnitude`() {
        assertContent(listOf(2.0 to 5.0),
            ad(ddea(2.0 to 0.0, allowZero = true), ddea(2.0 to -5.0)))
    }

    @Test
    fun `ad 17 large contiguous constant difference`() {
        val a = DoubleDoubleEntangledArray((0 until 400).map { it.toDouble() to 5.0 }, allowZero = false)
        val b = DoubleDoubleEntangledArray((0 until 400).map { it.toDouble() to 2.0 }, allowZero = false)
        assertContent((0 until 400).map { it.toDouble() to 3.0 }, ad(a, b))
    }

    @Test
    fun `ad 18 result can be diffed again`() {
        val first = ad(ddea(1.0 to 5.0, 2.0 to 2.0), ddea(1.0 to 1.0, 2.0 to 8.0)) // {1:4, 2:6}
        assertContent(listOf(1.0 to 3.0, 2.0 to 6.0), ad(first, ddea(1.0 to 1.0)))
    }

    @Test
    fun `ad 19 randomized matches reference`() {
        val rnd = Random(21)
        repeat(800) {
            val spread = rnd.nextBoolean()
            val a = setPairs(rnd, spread); val b = setPairs(rnd, spread)
            assertContent(referenceDiff(a, b), ad(build(a), build(b)))
        }
    }

    @Test
    fun `ad 20 equals first absolute when second empty`() {
        val a = ddea(1.0 to -2.0, 2.0 to 3.0, 3.0 to -4.0)
        Assertions.assertSame(EMPTY, ws(ad(a, EMPTY) to 1.0, a.absolute() to -1.0))
    }

    // =====================================================================================
    // absolute  — per-level magnitude, zeros dropped, all-positive input returned as-is
    // =====================================================================================

    /** Reference: |q| at every level with zeros dropped, ascending (inputs use unique levels). */
    private fun referenceAbs(a: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        require(a.toMap().size == a.size) { "reference assumes unique levels" }
        return a.sortedBy { it.first }
            .mapNotNull { (l, q) -> abs(q).let { if (it != 0.0) l to it else null } }
    }

    @Test
    fun `abs 01 EMPTY returns the EMPTY singleton`() {
        Assertions.assertSame(EMPTY, EMPTY.absolute())
    }

    @Test
    fun `abs 02 empty-constructed array returns EMPTY singleton`() {
        assertEmpty(ddea().absolute())
    }

    @Test
    fun `abs 03 all-zero array is EMPTY`() {
        assertEmpty(ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = true).absolute())
    }

    @Test
    fun `abs 04 all-positive array returns the same instance`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0)
        Assertions.assertSame(a, a.absolute())
    }

    @Test
    fun `abs 05 single positive returns the same instance`() {
        val a = ddea(7.0 to 1.0)
        Assertions.assertSame(a, a.absolute())
    }

    @Test
    fun `abs 06 idempotent - absolute of a result is all-positive so returns same instance`() {
        val once = ddea(1.0 to -2.0, 2.0 to 3.0).absolute()
        Assertions.assertSame(once, once.absolute())
    }

    @Test
    fun `abs 07 all-negative array maps to magnitudes`() {
        val a = ddea(1.0 to -2.0, 2.0 to -3.0, 3.0 to -4.0)
        val r = a.absolute()
        Assertions.assertNotSame(a, r)
        assertContent(listOf(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0), r)
    }

    @Test
    fun `abs 08 mixed signs map to magnitudes`() {
        assertContent(listOf(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0),
            ddea(1.0 to -2.0, 2.0 to 3.0, 3.0 to -4.0).absolute())
    }

    @Test
    fun `abs 09 single negative maps to its magnitude`() {
        val a = ddea(7.0 to -5.0)
        val r = a.absolute()
        Assertions.assertNotSame(a, r)
        assertContent(listOf(7.0 to 5.0), r)
    }

    @Test
    fun `abs 10 interior zeros dropped not same instance`() {
        val a = ddea(1.0 to -3.0, 2.0 to 0.0, 3.0 to 7.0, allowZero = true)
        val r = a.absolute()
        Assertions.assertNotSame(a, r)
        assertContent(listOf(1.0 to 3.0, 3.0 to 7.0), r)
    }

    @Test
    fun `abs 11 leading and trailing zeros dropped endpoints recompute`() {
        val r = ddea(1.0 to 0.0, 2.0 to 0.0, 3.0 to -7.0, 4.0 to 0.0, allowZero = true).absolute()
        assertContent(listOf(3.0 to 7.0), r)
        Assertions.assertEquals(3.0, r.firstLevel); Assertions.assertEquals(3.0, r.lastLevel); Assertions.assertEquals(1, r.size)
    }

    @Test
    fun `abs 12 large array with scattered negatives and zeros`() {
        val a = DoubleDoubleEntangledArray(
            (0 until 300).map { it.toDouble() to (if (it % 7 == 0) 0.0 else if (it % 2 == 0) -it.toDouble() else it.toDouble()) },
            allowZero = true,
        )
        assertContent((0 until 300).filter { it % 7 != 0 }.map { it.toDouble() to it.toDouble() }, a.absolute())
    }

    @Test
    fun `abs 13 large all-positive array returns same instance`() {
        val a = DoubleDoubleEntangledArray((0 until 300).map { it.toDouble() to (it + 1).toDouble() }, allowZero = false)
        Assertions.assertSame(a, a.absolute())
    }

    @Test
    fun `abs 14 randomized matches reference`() {
        val rnd = Random(31)
        repeat(800) {
            val spread = rnd.nextBoolean()
            val a = setPairs(rnd, spread)
            assertContent(referenceAbs(a), build(a).absolute())
        }
    }

    // =====================================================================================
    // head(n) / tail(n)  — narrowing views over the lowest / highest n entries (no copy)
    // =====================================================================================

    @Test
    fun `head 01 zero count is EMPTY singleton`() {
        assertEmpty(ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0).head(0))
    }

    @Test
    fun `head 02 negative count is EMPTY singleton`() {
        assertEmpty(ddea(1.0 to 1.0, 2.0 to 2.0).head(-3))
    }

    @Test
    fun `head 03 on EMPTY is EMPTY singleton`() {
        assertEmpty(EMPTY.head(5))
    }

    @Test
    fun `tail 01 zero count is EMPTY singleton`() {
        assertEmpty(ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0).tail(0))
    }

    @Test
    fun `tail 02 negative count is EMPTY singleton`() {
        assertEmpty(ddea(1.0 to 1.0, 2.0 to 2.0).tail(-1))
    }

    @Test
    fun `tail 03 on EMPTY is EMPTY singleton`() {
        assertEmpty(EMPTY.tail(5))
    }

    @Test
    fun `head 04 count equal to size returns the same instance`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        Assertions.assertSame(a, a.head(3))
    }

    @Test
    fun `head 05 count beyond size returns the same instance`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        Assertions.assertSame(a, a.head(99))
    }

    @Test
    fun `tail 04 count equal to size returns the same instance`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        Assertions.assertSame(a, a.tail(3))
    }

    @Test
    fun `tail 05 count beyond size returns the same instance`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        Assertions.assertSame(a, a.tail(99))
    }

    @Test
    fun `head 06 takes the lowest levels`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0)
        val h = a.head(2)
        assertContent(listOf(1.0 to 10.0, 2.0 to 20.0), h)
        Assertions.assertEquals(1.0, h.firstLevel); Assertions.assertEquals(2.0, h.lastLevel); Assertions.assertEquals(2, h.size)
    }

    @Test
    fun `head 07 single element window`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0)
        val h = a.head(1)
        assertContent(listOf(1.0 to 10.0), h)
        Assertions.assertEquals(1.0, h.firstLevel); Assertions.assertEquals(1.0, h.lastLevel)
    }

    @Test
    fun `tail 06 takes the highest levels`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0)
        val t = a.tail(2)
        assertContent(listOf(3.0 to 30.0, 4.0 to 40.0), t)
        Assertions.assertEquals(3.0, t.firstLevel); Assertions.assertEquals(4.0, t.lastLevel); Assertions.assertEquals(2, t.size)
    }

    @Test
    fun `tail 07 single element window`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0)
        val t = a.tail(1)
        assertContent(listOf(3.0 to 30.0), t)
        Assertions.assertEquals(3.0, t.firstLevel); Assertions.assertEquals(3.0, t.lastLevel)
    }

    @Test
    fun `head 08 negative levels and quantities`() {
        val a = ddea(-9.0 to -1.0, -4.0 to 2.0, -1.0 to -3.0, 5.0 to 4.0)
        assertContent(listOf(-9.0 to -1.0, -4.0 to 2.0), a.head(2))
    }

    @Test
    fun `tail 08 negative levels and quantities`() {
        val a = ddea(-9.0 to -1.0, -4.0 to 2.0, -1.0 to -3.0, 5.0 to 4.0)
        assertContent(listOf(-1.0 to -3.0, 5.0 to 4.0), a.tail(2))
    }

    @Test
    fun `head 09 view does not alter the source array`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0)
        a.head(2)
        assertContent(listOf(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0), ws(a to 1.0))
        Assertions.assertEquals(4, a.size); Assertions.assertEquals(1.0, a.firstLevel); Assertions.assertEquals(4.0, a.lastLevel)
    }

    @Test
    fun `head 10 head and complementary tail partition the array`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0, 5.0 to 50.0)
        Assertions.assertSame(EMPTY, ws(a.head(2) to 1.0, a.tail(3) to 1.0, a to -1.0))
    }

    @Test
    fun `head 11 view can be further narrowed (head of head)`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0)
        assertContent(listOf(1.0 to 10.0), a.head(3).head(1))
    }

    @Test
    fun `tail 09 view can be further narrowed (tail of tail)`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0)
        assertContent(listOf(4.0 to 40.0), a.tail(3).tail(1))
    }

    @Test
    fun `head 12 tail then head selects an interior window`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0, 5.0 to 50.0)
        assertContent(listOf(3.0 to 30.0, 4.0 to 40.0), a.tail(3).head(2))
    }

    @Test
    fun `tail 10 head then tail selects an interior window`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0, 5.0 to 50.0)
        assertContent(listOf(3.0 to 30.0, 4.0 to 40.0), a.head(4).tail(2))
    }

    @Test
    fun `head 13 keeps zero entries inside the window`() {
        val a = ddea(1.0 to 0.0, 2.0 to 5.0, 3.0 to 0.0, 4.0 to 7.0, allowZero = true)
        val h = a.head(2)
        Assertions.assertEquals(2, h.size); Assertions.assertEquals(1.0, h.firstLevel); Assertions.assertEquals(2.0, h.lastLevel)
    }

    @Test
    fun `tail 11 keeps zero entries inside the window`() {
        val a = ddea(1.0 to 5.0, 2.0 to 0.0, 3.0 to 7.0, 4.0 to 0.0, allowZero = true)
        val t = a.tail(2)
        Assertions.assertEquals(2, t.size); Assertions.assertEquals(3.0, t.firstLevel); Assertions.assertEquals(4.0, t.lastLevel)
    }

    @Test
    fun `head 14 large array window`() {
        val a = DoubleDoubleEntangledArray((0 until 1000).map { it.toDouble() to (it + 1).toDouble() }, allowZero = false)
        val h = a.head(250)
        assertContent((0 until 250).map { it.toDouble() to (it + 1).toDouble() }, h)
        Assertions.assertEquals(250, h.size); Assertions.assertEquals(0.0, h.firstLevel); Assertions.assertEquals(249.0, h.lastLevel)
    }

    @Test
    fun `tail 12 large array window`() {
        val a = DoubleDoubleEntangledArray((0 until 1000).map { it.toDouble() to (it + 1).toDouble() }, allowZero = false)
        val t = a.tail(250)
        assertContent((750 until 1000).map { it.toDouble() to (it + 1).toDouble() }, t)
        Assertions.assertEquals(250, t.size); Assertions.assertEquals(750.0, t.firstLevel); Assertions.assertEquals(999.0, t.lastLevel)
    }

    @Test
    fun `head tail 15 randomized windows match reference`() {
        val rnd = Random(41)
        repeat(800) {
            val spread = rnd.nextBoolean()
            val pairs = setPairs(rnd, spread).filter { it.second != 0.0 }
            if (pairs.isEmpty()) return@repeat
            val sorted = pairs.sortedBy { it.first }
            val a = build(sorted)
            val n = rnd.nextInt(1, a.size + 1)
            assertContent(sorted.take(n), a.head(n))
            assertContent(sorted.takeLast(n), a.tail(n))
        }
    }

    // =====================================================================================
    // trim(n)  — narrowing view over the n middle entries (no copy)
    // =====================================================================================

    @Test
    fun `trim 01 zero count is EMPTY singleton`() {
        assertEmpty(ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0).trim(0))
    }

    @Test
    fun `trim 02 negative count is EMPTY singleton`() {
        assertEmpty(ddea(1.0 to 1.0, 2.0 to 2.0).trim(-3))
    }

    @Test
    fun `trim 03 on EMPTY is EMPTY singleton`() {
        assertEmpty(EMPTY.trim(5))
    }

    @Test
    fun `trim 04 count equal to size returns the same instance`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        Assertions.assertSame(a, a.trim(3))
    }

    @Test
    fun `trim 05 count beyond size returns the same instance`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        Assertions.assertSame(a, a.trim(99))
    }

    @Test
    fun `trim 06 odd surplus keeps the centre and drops the extra from the high end`() {
        assertContent(listOf(3.0 to 30.0), ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0, 5.0 to 50.0).trim(1))
        assertContent(
            listOf(2.0 to 20.0, 3.0 to 30.0),
            ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0, 5.0 to 50.0).trim(2),
        )
    }

    @Test
    fun `trim 07 even surplus drops evenly from both ends`() {
        assertContent(
            listOf(2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0),
            ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0, 5.0 to 50.0).trim(3),
        )
        assertContent(listOf(2.0 to 20.0, 3.0 to 30.0), ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0).trim(2))
    }

    @Test
    fun `trim 08 single element window`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0)
        val t = a.trim(1)
        Assertions.assertEquals(1, t.size)
        Assertions.assertEquals(2.0, t.firstLevel)
        Assertions.assertEquals(2.0, t.lastLevel)
    }

    @Test
    fun `trim 09 negative levels and quantities`() {
        val a = ddea(-9.0 to -1.0, -4.0 to 2.0, -1.0 to -3.0, 5.0 to 4.0)
        assertContent(listOf(-4.0 to 2.0, -1.0 to -3.0), a.trim(2))
    }

    @Test
    fun `trim 10 view does not alter the source array`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0, 5.0 to 50.0)
        a.trim(3)
        assertContent(listOf(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0, 5.0 to 50.0), a)
    }

    @Test
    fun `trim 11 head trim tail partition the array`() {
        val a = ddea(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0, 4.0 to 40.0, 5.0 to 50.0, 6.0 to 60.0)
        val n = 2
        val dropFront = (a.size - n) / 2
        val highCount = a.size - n - dropFront
        Assertions.assertSame(
            EMPTY,
            ws(a.head(dropFront) to 1.0, a.trim(n) to 1.0, a.tail(highCount) to 1.0, a to -1.0),
        )
    }

    @Test
    fun `trim 12 keeps zero entries inside the window`() {
        val a = ddea(1.0 to 5.0, 2.0 to 0.0, 3.0 to 7.0, 4.0 to 0.0, 5.0 to 9.0, allowZero = true)
        val t = a.trim(3)
        Assertions.assertEquals(3, t.size)
        Assertions.assertEquals(2.0, t.firstLevel)
        Assertions.assertEquals(4.0, t.lastLevel)
    }

    @Test
    fun `trim 13 large array window`() {
        val a = DoubleDoubleEntangledArray((0 until 1000).map { it.toDouble() to (it + 1).toDouble() }, allowZero = false)
        val t = a.trim(250)
        assertContent((375 until 625).map { it.toDouble() to (it + 1).toDouble() }, t)
        Assertions.assertEquals(250, t.size)
        Assertions.assertEquals(375.0, t.firstLevel)
        Assertions.assertEquals(624.0, t.lastLevel)
    }

    @Test
    fun `trim 14 randomized windows match reference`() {
        val rnd = Random(43)
        repeat(800) {
            val spread = rnd.nextBoolean()
            val pairs = setPairs(rnd, spread).filter { it.second != 0.0 }
            if (pairs.isEmpty()) return@repeat
            val sorted = pairs.sortedBy { it.first }
            val a = build(sorted)
            val n = rnd.nextInt(1, a.size + 1)
            val dropFront = (a.size - n) / 2
            assertContent(sorted.drop(dropFront).take(n), a.trim(n))
        }
    }

    // =====================================================================================
    // serialize / deserialize
    // =====================================================================================

    private fun roundTrip(a: DoubleDoubleEntangledArray) =
        DoubleDoubleEntangledArray.deserialize(a.serialize())

    @Test
    fun `serialize empty array is four zero bytes`() {
        val bytes = ddea().serialize()
        Assertions.assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes)
    }

    @Test
    fun `deserialize empty array is the EMPTY singleton`() {
        assertEmpty(roundTrip(ddea()))
        assertEmpty(roundTrip(EMPTY))
    }

    @Test
    fun `serialize single element has the expected byte layout`() {
        // 4 (count) + 8 (level double) + 8 (quantity double) = 20
        val bytes = ddea(5.5 to 3.25).serialize()
        Assertions.assertEquals(20, bytes.size)

        val buffer = ByteBuffer.wrap(bytes)
        Assertions.assertEquals(1, buffer.getInt())          // entry count
        Assertions.assertEquals(5.5, buffer.getDouble())     // level
        Assertions.assertEquals(3.25, buffer.getDouble())    // quantity
    }

    @Test
    fun `round-trips a single element`() {
        assertContent(listOf(5.0 to 3.0), roundTrip(ddea(5.0 to 3.0)))
    }

    @Test
    fun `round-trips a contiguous run`() {
        val pairs = listOf(10.0 to 1.0, 11.0 to 2.0, 12.0 to 3.0, 13.0 to 4.0)
        assertContent(pairs, roundTrip(ddea(*pairs.toTypedArray())))
    }

    @Test
    fun `round-trips levels separated by gaps`() {
        val pairs = listOf(1.0 to 1.0, 2.0 to 2.0, 100.0 to 5.0, 101.0 to 6.0, 5000.0 to 9.0)
        assertContent(pairs, roundTrip(ddea(*pairs.toTypedArray())))
    }

    @Test
    fun `round-trips negative levels and negative quantities`() {
        val pairs = listOf(-5.0 to -2.0, -4.0 to 3.0, 0.0 to 1.0, 7.0 to -8.0)
        assertContent(pairs, roundTrip(ddea(*pairs.toTypedArray())))
    }

    @Test
    fun `round-trips fractional levels and quantities`() {
        val pairs = listOf(-5.25 to -2.5, 0.5 to 3.125, 1.75 to 0.0625, 1024.5 to -8.5)
        assertContent(pairs, roundTrip(ddea(*pairs.toTypedArray())))
    }

    @Test
    fun `round-trips very large and very small magnitude values`() {
        val pairs = listOf(-1.0e18 to 1.0e-9, 1.0e-3 to 2.0, 3.0e9 to -4.0e12)
        assertContent(pairs, roundTrip(ddea(*pairs.toTypedArray())))
    }

    @Test
    fun `round-trips a long run`() {
        val pairs = (0 until 300).map { (1000 + it).toDouble() to (it + 1).toDouble() }
        assertContent(pairs, roundTrip(build(pairs)))
    }

    @Test
    fun `deserialize rejects a truncated header`() {
        assertThrows<IllegalArgumentException> { DoubleDoubleEntangledArray.deserialize(byteArrayOf()) }
        assertThrows<IllegalArgumentException> { DoubleDoubleEntangledArray.deserialize(byteArrayOf(0, 0, 0)) }
    }

    @Test
    fun `deserialize rejects a truncated body`() {
        val bytes = ddea(1.0 to 1.0, 2.0 to 2.0).serialize()
        val truncated = bytes.copyOf(bytes.size - 3)
        assertThrows<IllegalArgumentException> { DoubleDoubleEntangledArray.deserialize(truncated) }
    }

    @Test
    fun `serialize then deserialize round-trips random arrays`() {
        val rnd = Random(20260615)
        repeat(500) {
            val n = rnd.nextInt(1, 50)
            var level = rnd.nextInt(-1000, 1000).toDouble()
            val pairs = ArrayList<Pair<Double, Double>>(n)
            repeat(n) {
                level += 0.5 * rnd.nextInt(1, 12) // strictly ascending, with occasional gaps
                val q = rnd.nextInt(-50, 50).toDouble()
                if (q != 0.0) pairs.add(level to q)
            }
            if (pairs.isEmpty()) return@repeat
            assertContent(pairs, roundTrip(build(pairs)))
        }
    }

    // =====================================================================================
    // zip (logarithmic-bucket aggregation -> IntDoubleEntangledArray)
    // =====================================================================================

    private val INT_EMPTY = IntDoubleEntangledArray.EMPTY

    private fun assertIntEmpty(actual: IntDoubleEntangledArray) {
        Assertions.assertSame(INT_EMPTY, actual)
        Assertions.assertEquals(0, actual.size)
    }

    /**
     * Asserts the zipped [IntDoubleEntangledArray] holds exactly [expected] (ascending buckets, no zeros,
     * no dup buckets), checked through the [IntDoubleEntangledArray] public-API cancellation identity.
     */
    private fun assertIntContent(expected: List<Pair<Int, Double>>, actual: IntDoubleEntangledArray) {
        val exp = expected.sortedBy { it.first }
        require(exp.map { it.first }.toSet().size == exp.size) { "expected has duplicate buckets" }
        require(exp.none { it.second == 0.0 }) { "expected has a zero quantity" }

        Assertions.assertEquals(exp.size, actual.size, "size")
        if (exp.isEmpty()) { assertIntEmpty(actual); return }

        Assertions.assertEquals(exp.first().first, actual.firstLevel, "firstLevel")
        Assertions.assertEquals(exp.last().first, actual.lastLevel, "lastLevel")

        val expectedArray = IntDoubleEntangledArray(exp, allowZero = false)
        val diff = IntDoubleEntangledArray.weightedSum(listOf(actual to 1.0, expectedArray to -1.0))
        Assertions.assertSame(INT_EMPTY, diff, "content differs: actual - expected is not empty")
    }

    /** The bucket an individual level falls into, matching the production formula. */
    private fun bucketOf(level: Double, invLnExp: Double): Int = (ln(level) * invLnExp).toInt()

    /** Reference: bucket each pair, sum within buckets, drop zero sums, ascending bucket order. */
    private fun zipReference(pairs: List<Pair<Double, Double>>, invLnExp: Double): List<Pair<Int, Double>> {
        val acc = sortedMapOf<Int, Double>()
        for ((level, value) in pairs) acc.merge(bucketOf(level, invLnExp), value, Double::plus)
        return acc.entries.filter { it.value != 0.0 }.map { it.key to it.value }
    }

    @Test
    fun `zip of an empty array is the IntDouble EMPTY singleton`() {
        assertIntEmpty(EMPTY.zip(100.0))
        assertIntEmpty(ddea().zip(100.0))
    }

    @Test
    fun `zip of a single entry yields one bucket`() {
        val invLnExp = 1.0 / ln(1.001)
        assertIntContent(listOf(bucketOf(100.0, invLnExp) to 7.0), ddea(100.0 to 7.0).zip(invLnExp))
    }

    @Test
    fun `zip sums entries sharing a bucket`() {
        // ratio ~ e (invLnExp = 1): 100, 100.5, 101 all map to the same integer bucket.
        val a = ddea(100.0 to 1.0, 100.5 to 2.0, 101.0 to 3.0)
        assertIntContent(listOf(bucketOf(100.0, 1.0) to 6.0), a.zip(1.0))
    }

    @Test
    fun `zip keeps separate totals for entries in different buckets`() {
        val pairs = listOf(100.0 to 1.0, 100.05 to 2.0, 110.0 to 5.0, 110.1 to 1.0, 200.0 to 9.0)
        val invLnExp = 1.0 / ln(1.001)
        assertIntContent(zipReference(pairs, invLnExp), ddea(*pairs.toTypedArray()).zip(invLnExp))
    }

    @Test
    fun `zip with a finer invLnExp yields at least as many buckets`() {
        val pairs = (0 until 200).map { (100.0 + it * 0.1) to 1.0 }
        val a = ddea(*pairs.toTypedArray())
        val coarse = a.zip(1.0 / ln(1.01)).size
        val fine = a.zip(1.0 / ln(1.001)).size
        Assertions.assertTrue(fine >= coarse, "finer buckets ($fine) should be >= coarser ($coarse)")
    }

    @Test
    fun `zip drops a bucket whose values cancel to zero`() {
        val a = ddea(100.0 to 5.0, 100.5 to -5.0, 200.0 to 3.0, allowZero = true)
        assertIntContent(listOf(bucketOf(200.0, 1.0) to 3.0), a.zip(1.0))
    }

    @Test
    fun `zip keeps a negative bucket total`() {
        val a = ddea(100.0 to 2.0, 100.5 to -5.0, allowZero = true)
        assertIntContent(listOf(bucketOf(100.0, 1.0) to -3.0), a.zip(1.0))
    }

    @Test
    fun `zip of everything cancelling away is the IntDouble EMPTY singleton`() {
        assertIntEmpty(ddea(100.0 to 5.0, 100.5 to -5.0, allowZero = true).zip(1.0))
    }

    @Test
    fun `zip of a window only aggregates that window`() {
        val pairs = (0 until 20).map { (100.0 + it) to 1.0 }
        val a = ddea(*pairs.toTypedArray())
        val invLnExp = 1.0 / ln(1.005)
        assertIntContent(zipReference(pairs.subList(5, 15), invLnExp), a.tail(15).head(10).zip(invLnExp))
    }

    @Test
    fun `zip matches the reference including negatives and cancellations`() {
        val rnd = Random(7)
        repeat(500) {
            val n = rnd.nextInt(1, 40)
            var price = 10.0 + rnd.nextDouble() * 90.0
            val pairs = ArrayList<Pair<Double, Double>>(n)
            repeat(n) {
                price += rnd.nextDouble() * 1.5 + 1e-3 // strictly ascending positive prices
                val q = if (rnd.nextDouble() < 0.2) 0.0 else rnd.nextInt(-30, 31).toDouble()
                pairs.add(price to q)
            }
            val invLnExp = 1.0 / ln(1.0 + rnd.nextDouble() * 0.1)
            assertIntContent(zipReference(pairs, invLnExp), ddea(*pairs.toTypedArray()).zip(invLnExp))
        }
    }

    // =====================================================================================
    // dropAfter / dropStrictAfter / dropBefore / dropStrictBefore
    // =====================================================================================

    /** Levels 1,3,5,7,9 (ascending, with gaps so `num` can fall between entries). */
    private fun dropSample() = ddea(1.0 to 10.0, 3.0 to 20.0, 5.0 to 30.0, 7.0 to 40.0, 9.0 to 50.0)

    @Test
    fun `dropAfter keeps levels at or below num`() {
        assertContent(listOf(1.0 to 10.0, 3.0 to 20.0, 5.0 to 30.0), dropSample().dropAfter(5.0))
        assertContent(listOf(1.0 to 10.0, 3.0 to 20.0, 5.0 to 30.0), dropSample().dropAfter(6.0)) // num absent
    }

    @Test
    fun `dropAfter returns this when nothing is above num`() {
        val a = dropSample()
        Assertions.assertSame(a, a.dropAfter(9.0))
        Assertions.assertSame(a, a.dropAfter(100.0))
    }

    @Test
    fun `dropAfter is EMPTY when everything is above num`() {
        assertEmpty(dropSample().dropAfter(0.0))
    }

    @Test
    fun `dropStrictAfter keeps levels strictly below num`() {
        assertContent(listOf(1.0 to 10.0, 3.0 to 20.0), dropSample().dropStrictAfter(5.0))
        assertContent(listOf(1.0 to 10.0, 3.0 to 20.0, 5.0 to 30.0), dropSample().dropStrictAfter(6.0)) // num absent
    }

    @Test
    fun `dropStrictAfter returns this when num is above every level`() {
        val a = dropSample()
        Assertions.assertSame(a, a.dropStrictAfter(10.0))
    }

    @Test
    fun `dropStrictAfter is EMPTY when num is at or below the first level`() {
        assertEmpty(dropSample().dropStrictAfter(1.0))
        assertEmpty(dropSample().dropStrictAfter(0.0))
    }

    @Test
    fun `dropBefore keeps levels at or above num`() {
        assertContent(listOf(5.0 to 30.0, 7.0 to 40.0, 9.0 to 50.0), dropSample().dropBefore(5.0))
        assertContent(listOf(7.0 to 40.0, 9.0 to 50.0), dropSample().dropBefore(6.0)) // num absent
    }

    @Test
    fun `dropBefore returns this when nothing is below num`() {
        val a = dropSample()
        Assertions.assertSame(a, a.dropBefore(1.0))
        Assertions.assertSame(a, a.dropBefore(0.0))
    }

    @Test
    fun `dropBefore is EMPTY when everything is below num`() {
        assertEmpty(dropSample().dropBefore(10.0))
    }

    @Test
    fun `dropStrictBefore keeps levels strictly above num`() {
        assertContent(listOf(7.0 to 40.0, 9.0 to 50.0), dropSample().dropStrictBefore(5.0))
        assertContent(listOf(7.0 to 40.0, 9.0 to 50.0), dropSample().dropStrictBefore(6.0)) // num absent
    }

    @Test
    fun `dropStrictBefore returns this when num is below every level`() {
        val a = dropSample()
        Assertions.assertSame(a, a.dropStrictBefore(0.0))
    }

    @Test
    fun `dropStrictBefore is EMPTY when num is at or above the last level`() {
        assertEmpty(dropSample().dropStrictBefore(9.0))
        assertEmpty(dropSample().dropStrictBefore(100.0))
    }

    @Test
    fun `drops on an empty array are the EMPTY singleton`() {
        assertEmpty(EMPTY.dropAfter(5.0))
        assertEmpty(EMPTY.dropStrictAfter(5.0))
        assertEmpty(EMPTY.dropBefore(5.0))
        assertEmpty(EMPTY.dropStrictBefore(5.0))
    }

    @Test
    fun `drops compose to carve out a middle range`() {
        assertContent(listOf(3.0 to 20.0, 5.0 to 30.0, 7.0 to 40.0), dropSample().dropBefore(3.0).dropAfter(7.0))
        assertContent(listOf(5.0 to 30.0), dropSample().dropStrictBefore(3.0).dropStrictAfter(7.0))
    }

    @Test
    fun `drops match a filter reference for random arrays`() {
        val rnd = Random(31)
        repeat(500) {
            val n = rnd.nextInt(1, 40)
            var level = rnd.nextInt(-1000, 1000).toDouble()
            val pairs = ArrayList<Pair<Double, Double>>(n)
            repeat(n) {
                level += 0.5 * rnd.nextInt(1, 12) // strictly ascending unique levels
                pairs.add(level to rnd.nextInt(1, 50).toDouble())
            }
            val a = ddea(*pairs.toTypedArray())
            val num = rnd.nextInt(-1100, 1100).toDouble() + (if (rnd.nextBoolean()) 0.25 else 0.0)
            assertContent(pairs.filter { it.first <= num }, a.dropAfter(num))
            assertContent(pairs.filter { it.first < num }, a.dropStrictAfter(num))
            assertContent(pairs.filter { it.first >= num }, a.dropBefore(num))
            assertContent(pairs.filter { it.first > num }, a.dropStrictBefore(num))
        }
    }

    // =====================================================================================
    // firstNonZeroLevel / lastNonZeroLevel
    // =====================================================================================

    @Test
    fun `non-zero levels equal the extremes for a canonical array`() {
        val a = ddea(1.0 to 5.0, 3.0 to 7.0, 5.0 to 9.0)
        Assertions.assertEquals(1.0, a.firstNonZeroLevel())
        Assertions.assertEquals(5.0, a.lastNonZeroLevel())
    }

    @Test
    fun `firstNonZeroLevel skips leading zeros`() {
        val a = ddea(1.0 to 0.0, 2.0 to 0.0, 3.0 to 5.0, 4.0 to 7.0, allowZero = true)
        Assertions.assertEquals(3.0, a.firstNonZeroLevel())
    }

    @Test
    fun `lastNonZeroLevel skips trailing zeros`() {
        val a = ddea(1.0 to 5.0, 2.0 to 7.0, 3.0 to 0.0, 4.0 to 0.0, allowZero = true)
        Assertions.assertEquals(2.0, a.lastNonZeroLevel())
    }

    @Test
    fun `non-zero levels skip zeros on both ends`() {
        val a = ddea(1.0 to 0.0, 2.0 to 5.0, 3.0 to 0.0, 4.0 to 7.0, 5.0 to 0.0, allowZero = true)
        Assertions.assertEquals(2.0, a.firstNonZeroLevel())
        Assertions.assertEquals(4.0, a.lastNonZeroLevel())
    }

    @Test
    fun `non-zero levels on an all-zero array are null`() {
        val a = ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = true)
        Assertions.assertNull(a.firstNonZeroLevel())
        Assertions.assertNull(a.lastNonZeroLevel())
    }

    @Test
    fun `non-zero levels on an empty array are null`() {
        Assertions.assertNull(EMPTY.firstNonZeroLevel())
        Assertions.assertNull(ddea().lastNonZeroLevel())
    }

    @Test
    fun `non-zero levels respect the window`() {
        val a = ddea(1.0 to 5.0, 2.0 to 0.0, 3.0 to 7.0, 4.0 to 0.0, allowZero = true)
        val w = a.tail(2) // window over (3.0, 7.0) and (4.0, 0.0)
        Assertions.assertEquals(3.0, w.firstNonZeroLevel())
        Assertions.assertEquals(3.0, w.lastNonZeroLevel())
    }

    // =====================================================================================
    // toLevelValueList
    // =====================================================================================

    @Test
    fun `toLevelValueList of an empty array is empty`() {
        Assertions.assertEquals(emptyList<List<Number>>(), EMPTY.toLevelValueList())
        Assertions.assertEquals(emptyList<List<Number>>(), ddea().toLevelValueList())
    }

    @Test
    fun `toLevelValueList returns level-value rows in ascending order`() {
        val a = ddea(5.0 to 7.0, 1.0 to 3.0, 3.0 to 9.0)
        Assertions.assertEquals(
            listOf(listOf<Number>(1.0, 3.0), listOf<Number>(3.0, 9.0), listOf<Number>(5.0, 7.0)),
            a.toLevelValueList(),
        )
    }

    @Test
    fun `toLevelValueList descending returns rows from highest level to lowest`() {
        val a = ddea(5.0 to 7.0, 1.0 to 3.0, 3.0 to 9.0)
        Assertions.assertEquals(
            listOf(listOf<Number>(5.0, 7.0), listOf<Number>(3.0, 9.0), listOf<Number>(1.0, 3.0)),
            a.toLevelValueList(descending = true),
        )
    }

    @Test
    fun `toLevelValueList includes zero entries`() {
        val a = ddea(1.0 to 0.0, 2.0 to 5.0, allowZero = true)
        Assertions.assertEquals(
            listOf(listOf<Number>(1.0, 0.0), listOf<Number>(2.0, 5.0)),
            a.toLevelValueList(),
        )
    }

    @Test
    fun `toLevelValueList respects the window`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0, 4.0 to 4.0)
        Assertions.assertEquals(
            listOf(listOf<Number>(2.0, 2.0), listOf<Number>(3.0, 3.0)),
            a.tail(3).head(2).toLevelValueList(),
        )
    }

    // =====================================================================================
    // sum  (unit-multiplier weightedSum)
    // =====================================================================================

    private fun sm(vararg arrays: DoubleDoubleEntangledArray) =
        DoubleDoubleEntangledArray.sum(arrays.toList())

    /** Asserts [actual] holds the same canonical content as [expected] (both already canonical). */
    private fun assertSameContent(expected: DoubleDoubleEntangledArray, actual: DoubleDoubleEntangledArray) {
        Assertions.assertEquals(expected.size, actual.size, "size")
        Assertions.assertSame(EMPTY, ws(actual to 1.0, expected to -1.0), "content differs from expected")
    }

    @Test
    fun `sum empty list is EMPTY`() { assertEmpty(DoubleDoubleEntangledArray.sum(emptyList())) }

    @Test
    fun `sum single empty array is EMPTY`() { assertEmpty(sm(EMPTY)) }

    @Test
    fun `sum all empty arrays is EMPTY`() { assertEmpty(sm(EMPTY, ddea(), EMPTY)) }

    @Test
    fun `sum single canonical array returns the same instance`() {
        val a = ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0)
        Assertions.assertSame(a, sm(a))
    }

    @Test
    fun `sum single array with zeros drops them`() {
        val a = ddea(1.0 to 2.0, 2.0 to 0.0, 3.0 to 4.0, 4.0 to 0.0, allowZero = true)
        assertContent(listOf(1.0 to 2.0, 3.0 to 4.0), sm(a))
    }

    @Test
    fun `sum single all-zero array is EMPTY`() {
        assertEmpty(sm(ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = true)))
    }

    @Test
    fun `sum two disjoint`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0, 4.0 to 4.0),
            sm(ddea(1.0 to 1.0, 2.0 to 2.0), ddea(3.0 to 3.0, 4.0 to 4.0)))
    }

    @Test
    fun `sum two overlapping sums shared levels`() {
        assertContent(listOf(1.0 to 1.0, 2.0 to 5.0, 3.0 to 3.0),
            sm(ddea(1.0 to 1.0, 2.0 to 2.0), ddea(2.0 to 3.0, 3.0 to 3.0)))
    }

    @Test
    fun `sum two full cancellation is EMPTY`() {
        assertEmpty(sm(ddea(1.0 to 2.0, 2.0 to 3.0), ddea(1.0 to -2.0, 2.0 to -3.0)))
    }

    @Test
    fun `sum two partial cancellation drops cancelled level`() {
        assertContent(listOf(2.0 to 3.0), sm(ddea(1.0 to 2.0, 2.0 to 3.0), ddea(1.0 to -2.0)))
    }

    @Test
    fun `sum three overlapping via heap`() {
        assertContent(listOf(1.0 to 3.0, 2.0 to 2.0, 3.0 to 1.0),
            sm(ddea(1.0 to 1.0, 2.0 to 1.0), ddea(1.0 to 1.0, 3.0 to 1.0), ddea(1.0 to 1.0, 2.0 to 1.0)))
    }

    @Test
    fun `sum N spread`() {
        assertContent(listOf(0.0 to 1.0, 500.0 to 2.0, 1000.0 to 3.0),
            sm(ddea(0.0 to 1.0), ddea(500.0 to 2.0), ddea(1000.0 to 3.0)))
    }

    @Test
    fun `sum ignores empty terms and keeps order`() {
        assertContent(listOf(1.0 to 1.0, 5.0 to 2.0), sm(EMPTY, ddea(1.0 to 1.0), EMPTY, ddea(5.0 to 2.0)))
    }

    @Test
    fun `sum equivalent to weightedSum with unit multipliers - randomized`() {
        val rnd = Random(0xBEEF)
        repeat(3000) {
            val count = rnd.nextInt(1, 6)
            val maxLevel = if (rnd.nextBoolean()) 8 else 400
            val arrays = List(count) {
                val len = rnd.nextInt(0, 6)
                val pairs = (0 until len).map { rnd.nextInt(0, maxLevel).toDouble() to rnd.nextInt(-4, 5).toDouble() }
                DoubleDoubleEntangledArray(pairs, allowZero = true)
            }
            val viaWs = DoubleDoubleEntangledArray.weightedSum(arrays.map { it to 1.0 })
            val viaSum = DoubleDoubleEntangledArray.sum(arrays)
            assertSameContent(viaWs, viaSum)
        }
    }
}
