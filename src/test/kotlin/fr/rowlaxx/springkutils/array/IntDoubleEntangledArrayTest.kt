package fr.rowlaxx.springkutils.array

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.random.Random

/**
 * Black-box tests for [IntDoubleEntangledArray] exercised entirely through its public API
 * (constructor, [IntDoubleEntangledArray.times], [IntDoubleEntangledArray.div], `size`,
 * `firstLevel`, `lastLevel`, [IntDoubleEntangledArray.Companion.weightedSum] and `EMPTY`).
 *
 * The type exposes no element accessor, so result content is verified with the public-API identity
 * `A == B  <=>  weightedSum([A to 1, B to -1])` is empty (equal arrays cancel level-by-level and the
 * zero results are dropped). Empty results are always the [IntDoubleEntangledArray.EMPTY] singleton.
 * All quantities/multipliers are exactly representable so the cancellation is bit-exact.
 */
class IntDoubleEntangledArrayTest {

    private val EMPTY = IntDoubleEntangledArray.EMPTY

    private fun idea(vararg pairs: Pair<Int, Double>, allowZero: Boolean = false) =
        IntDoubleEntangledArray(pairs.toList(), allowZero)

    private fun ws(vararg terms: Pair<IntDoubleEntangledArray, Double>) =
        IntDoubleEntangledArray.weightedSum(terms.toList())

    private fun assertEmpty(actual: IntDoubleEntangledArray) {
        Assertions.assertSame(EMPTY, actual)
        Assertions.assertEquals(0, actual.size)
    }

    /** Asserts [actual] holds exactly [expected] (canonical: ascending levels, no zeros, no dup levels). */
    private fun assertContent(expected: List<Pair<Int, Double>>, actual: IntDoubleEntangledArray) {
        val exp = expected.sortedBy { it.first }
        require(exp.map { it.first }.toSet().size == exp.size) { "expected has duplicate levels" }
        require(exp.none { it.second == 0.0 }) { "expected has a zero quantity" }

        Assertions.assertEquals(exp.size, actual.size, "size")
        if (exp.isEmpty()) { assertEmpty(actual); return }

        Assertions.assertEquals(exp.first().first, actual.firstLevel, "firstLevel")
        Assertions.assertEquals(exp.last().first, actual.lastLevel, "lastLevel")

        // content check: actual - expected must vanish entirely
        val expectedArray = IntDoubleEntangledArray(exp, allowZero = false)
        val diff = ws(actual to 1.0, expectedArray to -1.0)
        Assertions.assertSame(EMPTY, diff, "content differs: actual - expected is not empty")
    }

    private fun assertContent(expected: List<Pair<Int, Double>>, vararg terms: Pair<IntDoubleEntangledArray, Double>) =
        assertContent(expected, ws(*terms))

    // =====================================================================================
    // constructor(pairs, allowZero)
    // =====================================================================================

    @Test
    fun `ctor empty collection is EMPTY-like`() {
        val a = idea()
        Assertions.assertEquals(0, a.size)
    }

    @Test
    fun `ctor single element`() {
        val a = idea(5 to 7.0)
        Assertions.assertEquals(1, a.size); Assertions.assertEquals(5, a.firstLevel); Assertions.assertEquals(
            5,
            a.lastLevel
        )
        assertContent(listOf(5 to 7.0), ws(a to 1.0))
    }

    @Test
    fun `ctor already ascending keeps order and endpoints`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        Assertions.assertEquals(3, a.size); Assertions.assertEquals(1, a.firstLevel); Assertions.assertEquals(
            3,
            a.lastLevel
        )
    }

    @Test
    fun `ctor unsorted input is sorted ascending`() {
        val a = idea(3 to 30.0, 1 to 10.0, 2 to 20.0)
        Assertions.assertEquals(1, a.firstLevel); Assertions.assertEquals(3, a.lastLevel)
        assertContent(listOf(1 to 10.0, 2 to 20.0, 3 to 30.0), ws(a to 1.0))
    }

    @Test
    fun `ctor reverse sorted input is sorted ascending`() {
        val a = idea(9 to 9.0, 7 to 7.0, 5 to 5.0, 3 to 3.0, 1 to 1.0)
        assertContent(listOf(1 to 1.0, 3 to 3.0, 5 to 5.0, 7 to 7.0, 9 to 9.0), ws(a to 1.0))
    }

    @Test
    fun `ctor duplicate levels are fused by summing quantities`() {
        val a = idea(1 to 2.0, 1 to 3.0, 2 to 5.0)
        Assertions.assertEquals(2, a.size)
        assertContent(listOf(1 to 5.0, 2 to 5.0), ws(a to 1.0))
    }

    @Test
    fun `ctor duplicate levels fusing to zero are dropped when allowZero false`() {
        val a = idea(1 to 2.0, 1 to -2.0, 2 to 5.0, allowZero = false)
        Assertions.assertEquals(1, a.size); Assertions.assertEquals(2, a.firstLevel)
        assertContent(listOf(2 to 5.0), ws(a to 1.0))
    }

    @Test
    fun `ctor duplicate levels fusing to zero are kept when allowZero true`() {
        val a = idea(1 to 2.0, 1 to -2.0, 2 to 5.0, allowZero = true)
        Assertions.assertEquals(2, a.size); Assertions.assertEquals(1, a.firstLevel); Assertions.assertEquals(
            2,
            a.lastLevel
        )
    }

    @Test
    fun `ctor zeros dropped when allowZero false`() {
        val a = idea(1 to 0.0, 2 to 5.0, 3 to 0.0, allowZero = false)
        Assertions.assertEquals(1, a.size); Assertions.assertEquals(2, a.firstLevel); Assertions.assertEquals(
            2,
            a.lastLevel
        )
    }

    @Test
    fun `ctor zeros kept when allowZero true`() {
        val a = idea(1 to 0.0, 2 to 5.0, 3 to 0.0, allowZero = true)
        Assertions.assertEquals(3, a.size); Assertions.assertEquals(1, a.firstLevel); Assertions.assertEquals(
            3,
            a.lastLevel
        )
    }

    @Test
    fun `ctor all zeros with allowZero false is empty`() {
        val a = idea(1 to 0.0, 2 to 0.0, allowZero = false)
        Assertions.assertEquals(0, a.size)
    }

    @Test
    fun `ctor negative levels`() {
        val a = idea(-5 to 1.0, -1 to 2.0, -3 to 3.0)
        Assertions.assertEquals(-5, a.firstLevel); Assertions.assertEquals(-1, a.lastLevel)
        assertContent(listOf(-5 to 1.0, -3 to 3.0, -1 to 2.0), ws(a to 1.0))
    }

    @Test
    fun `ctor many duplicates triggering compaction branch`() {
        // 100 entries all on 5 levels -> 95 fused; fused*10 > n triggers the copyOf compaction path.
        val pairs = (0 until 100).map { (it % 5) to 1.0 }
        val a = IntDoubleEntangledArray(pairs, allowZero = false)
        Assertions.assertEquals(5, a.size); Assertions.assertEquals(0, a.firstLevel); Assertions.assertEquals(
            4,
            a.lastLevel
        )
        assertContent((0 until 5).map { it to 20.0 }, ws(a to 1.0))
    }

    @Test
    fun `ctor large sorted run`() {
        val a = IntDoubleEntangledArray((0 until 1000).map { it to (it + 1).toDouble() }, allowZero = false)
        Assertions.assertEquals(1000, a.size); Assertions.assertEquals(0, a.firstLevel); Assertions.assertEquals(
            999,
            a.lastLevel
        )
    }

    // =====================================================================================
    // times
    // =====================================================================================

    @Test
    fun `times by zero returns EMPTY singleton`() {
        assertEmpty(idea(1 to 2.0, 2 to 3.0).times(0.0))
    }

    @Test
    fun `times by one returns same instance`() {
        val a = idea(1 to 2.0, 2 to 3.0)
        Assertions.assertSame(a, a.times(1.0))
    }

    @Test
    fun `times on empty returns EMPTY`() {
        assertEmpty(EMPTY.times(2.0))
    }

    @Test
    fun `times scales quantities`() {
        val a = idea(1 to 2.0, 2 to 3.0, 3 to 4.0)
        val scaled = a.times(2.0)
        Assertions.assertEquals(a.size, scaled.size)
        Assertions.assertSame(EMPTY, ws(scaled to 1.0, a to -2.0))
    }

    @Test
    fun `times by negative`() {
        val a = idea(1 to 2.0, 2 to 3.0)
        Assertions.assertSame(EMPTY, ws(a.times(-1.5) to 1.0, a to 1.5))
    }

    @Test
    fun `times keeps zero entries (does not drop)`() {
        val a = idea(1 to 0.0, 2 to 5.0, 3 to 0.0, allowZero = true)
        val scaled = a.times(2.0)
        Assertions.assertEquals(3, scaled.size); Assertions.assertEquals(1, scaled.firstLevel); Assertions.assertEquals(
            3,
            scaled.lastLevel
        )
    }

    @Test
    fun `times by fraction`() {
        val a = idea(1 to 4.0, 2 to 6.0)
        assertContent(listOf(1 to 2.0, 2 to 3.0), ws(a.times(0.5) to 1.0))
    }

    @Test
    fun `times preserves levels`() {
        val a = idea(-2 to 1.0, 7 to 2.0)
        val scaled = a.times(3.0)
        Assertions.assertEquals(-2, scaled.firstLevel); Assertions.assertEquals(7, scaled.lastLevel)
    }

    // =====================================================================================
    // div
    // =====================================================================================

    @Test
    fun `div by one returns same instance`() {
        val a = idea(1 to 2.0, 2 to 3.0)
        Assertions.assertSame(a, a.div(1.0))
    }

    @Test
    fun `div on empty returns EMPTY`() {
        assertEmpty(EMPTY.div(2.0))
    }

    @Test
    fun `div scales quantities`() {
        val a = idea(2 to 8.0, 4 to 16.0)
        assertContent(listOf(2 to 4.0, 4 to 8.0), ws(a.div(2.0) to 1.0))
    }

    @Test
    fun `div by negative`() {
        val a = idea(1 to 6.0, 2 to 9.0)
        assertContent(listOf(1 to -2.0, 2 to -3.0), ws(a.div(-3.0) to 1.0))
    }

    @Test
    fun `div keeps zero entries`() {
        val a = idea(1 to 0.0, 2 to 4.0, allowZero = true)
        val divided = a.div(2.0)
        Assertions.assertEquals(2, divided.size); Assertions.assertEquals(1, divided.firstLevel)
    }

    @Test
    fun `times then div round trips`() {
        val a = idea(1 to 3.0, 5 to 7.0, 9 to 11.0)
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
        val a = idea(42 to 1.0)
        Assertions.assertEquals(42, a.firstLevel); Assertions.assertEquals(42, a.lastLevel)
    }

    @Test
    fun `firstLevel and lastLevel reflect the extremes`() {
        val a = idea(10 to 1.0, 20 to 1.0, 30 to 1.0)
        Assertions.assertEquals(10, a.firstLevel); Assertions.assertEquals(30, a.lastLevel)
    }

    @Test
    fun `empty constructed array firstLevel throws`() {
        val a = idea()
        Assertions.assertThrows(IndexOutOfBoundsException::class.java) { a.firstLevel }
    }

    // =====================================================================================
    // weightedSum  (50+ tests)
    // =====================================================================================

    // ---- degenerate / filtering (n == 0) ------------------------------------------------

    @Test
    fun `ws 01 empty term list is EMPTY`() { assertEmpty(ws()) }

    @Test
    fun `ws 02 single term with zero multiplier is EMPTY`() {
        assertEmpty(ws(idea(1 to 2.0) to 0.0))
    }

    @Test
    fun `ws 03 all terms zero multiplier is EMPTY`() {
        assertEmpty(ws(idea(1 to 2.0) to 0.0, idea(2 to 3.0) to 0.0, idea(3 to 4.0) to 0.0))
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
        assertEmpty(ws(EMPTY to 5.0, idea(1 to 1.0) to 0.0))
    }

    // ---- n == 1 (scale path) ------------------------------------------------------------

    @Test
    fun `ws 07 one array doubled`() {
        assertContent(listOf(1 to 4.0, 2 to 6.0, 3 to 8.0), ws(idea(1 to 2.0, 2 to 3.0, 3 to 4.0) to 2.0))
    }

    @Test
    fun `ws 08 one array multiplier one is unchanged content`() {
        val a = idea(1 to 2.0, 2 to 3.0)
        assertContent(listOf(1 to 2.0, 2 to 3.0), ws(a to 1.0))
    }

    @Test
    fun `ws 09 one array negated`() {
        assertContent(listOf(1 to -2.0, 5 to -3.0), ws(idea(1 to 2.0, 5 to 3.0) to 1.0).times(-1.0))
    }

    @Test
    fun `ws 10 one array with zeros drops them`() {
        val a = idea(1 to 2.0, 2 to 0.0, 3 to 4.0, 4 to 0.0, 5 to 5.0, allowZero = true)
        assertContent(listOf(1 to 6.0, 3 to 12.0, 5 to 15.0), ws(a to 3.0))
    }

    @Test
    fun `ws 11 one all-zero array is EMPTY`() {
        assertEmpty(ws(idea(1 to 0.0, 2 to 0.0, allowZero = true) to 4.0))
    }

    @Test
    fun `ws 12 one single element scaled`() {
        assertContent(listOf(7 to 5.0), ws(idea(7 to 2.5) to 2.0))
    }

    @Test
    fun `ws 13 one array negative levels scaled`() {
        assertContent(listOf(-9 to -8.0, -4 to -6.0, -1 to -2.0),
            ws(idea(-9 to 4.0, -4 to 3.0, -1 to 1.0) to -2.0))
    }

    @Test
    fun `ws 14 one array fractional multiplier`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0), ws(idea(1 to 2.0, 2 to 4.0) to 0.5))
    }

    @Test
    fun `ws 15 one large array scaled`() {
        val a = IntDoubleEntangledArray((0 until 500).map { it to (it + 1).toDouble() }, allowZero = false)
        val r = ws(a to 2.0)
        Assertions.assertEquals(500, r.size); Assertions.assertEquals(0, r.firstLevel); Assertions.assertEquals(
            499,
            r.lastLevel
        )
        Assertions.assertSame(EMPTY, ws(r to 1.0, a to -2.0))
    }

    @Test
    fun `ws 16 one real array plus zero-multiplier arrays`() {
        assertContent(listOf(1 to 4.0, 2 to 6.0),
            ws(idea(1 to 2.0, 2 to 3.0) to 2.0, idea(9 to 9.0) to 0.0, idea(8 to 8.0) to 0.0))
    }

    @Test
    fun `ws 17 one real array plus empty arrays`() {
        assertContent(listOf(1 to 2.0, 2 to 3.0), ws(idea(1 to 2.0, 2 to 3.0) to 1.0, EMPTY to 5.0))
    }

    // ---- n == 2 (dense + merge) ---------------------------------------------------------

    @Test
    fun `ws 18 two contiguous fully overlapping`() {
        assertContent(listOf(1 to 7.0, 2 to 9.0, 3 to 11.0),
            ws(idea(1 to 2.0, 2 to 3.0, 3 to 4.0) to 2.0, idea(1 to 1.0, 2 to 1.0, 3 to 1.0) to 3.0))
    }

    @Test
    fun `ws 19 two contiguous partial overlap`() {
        assertContent(listOf(1 to 4.0, 2 to 11.0, 3 to 4.0),
            ws(idea(1 to 2.0, 2 to 3.0) to 2.0, idea(2 to 5.0, 3 to 4.0) to 1.0))
    }

    @Test
    fun `ws 20 two contiguous disjoint adjacent`() {
        assertContent(listOf(1 to 2.0, 2 to 4.0, 3 to 9.0, 4 to 12.0),
            ws(idea(1 to 1.0, 2 to 2.0) to 2.0, idea(3 to 3.0, 4 to 4.0) to 3.0))
    }

    @Test
    fun `ws 21 two full cancellation is EMPTY`() {
        val a = idea(1 to 2.0, 2 to 3.0, 3 to 4.0)
        assertEmpty(ws(a to 1.0, a to -1.0))
    }

    @Test
    fun `ws 22 two partial cancellation drops cancelled levels`() {
        assertContent(listOf(2 to 2.0),
            ws(idea(1 to 2.0, 2 to 3.0, 3 to 4.0) to 1.0, idea(1 to 2.0, 2 to 1.0, 3 to 4.0) to -1.0))
    }

    @Test
    fun `ws 23 two spread disjoint take merge path`() {
        assertContent(listOf(10 to 4.0, 500 to 5.0, 1000 to 6.0, 100000 to 4.0),
            ws(idea(10 to 2.0, 1000 to 3.0) to 2.0, idea(500 to 5.0, 100000 to 4.0) to 1.0))
    }

    @Test
    fun `ws 24 two spread overlap sums shared`() {
        assertContent(listOf(10 to 5.0, 5000 to 6.0, 999999 to 4.0),
            ws(idea(10 to 2.0, 5000 to 3.0) to 2.0, idea(10 to 1.0, 999999 to 4.0) to 1.0))
    }

    @Test
    fun `ws 25 two spread cancellation drops shared level`() {
        assertContent(listOf(5000 to 3.0, 999999 to -4.0),
            ws(idea(10 to 2.0, 5000 to 3.0) to 1.0, idea(10 to 2.0, 999999 to 4.0) to -1.0))
    }

    @Test
    fun `ws 26 two different sizes`() {
        assertContent(listOf(1 to 2.0, 1000 to 1.0, 1000000 to 6.0),
            ws(idea(1 to 1.0, 1000 to 1.0, 1000000 to 1.0) to 1.0, idea(1 to 1.0, 1000000 to 5.0) to 1.0))
    }

    @Test
    fun `ws 27 two single element same level`() {
        assertContent(listOf(7 to 9.0), ws(idea(7 to 2.0) to 2.0, idea(7 to 5.0) to 1.0))
    }

    @Test
    fun `ws 28 two single element different levels`() {
        assertContent(listOf(3 to 2.0, 8 to 5.0), ws(idea(3 to 1.0) to 2.0, idea(8 to 5.0) to 1.0))
    }

    @Test
    fun `ws 29 two contiguous with holes`() {
        // union {0,1,4,5}; holes at 2,3 (dense path: span+1=6 <= s=8)
        assertContent(listOf(0 to 3.0, 1 to 4.0, 4 to 5.0, 5 to 6.0),
            ws(idea(0 to 2.0, 1 to 3.0, 4 to 4.0, 5 to 5.0) to 1.0, idea(0 to 1.0, 1 to 1.0, 4 to 1.0, 5 to 1.0) to 1.0))
    }

    @Test
    fun `ws 30 two holes forcing merge path`() {
        // a={0,1}, b={3,4}; hole at 2; span+1=5 > s=4 -> merge
        assertContent(listOf(0 to 4.0, 1 to 6.0, 3 to 5.0, 4 to 6.0),
            ws(idea(0 to 2.0, 1 to 3.0) to 2.0, idea(3 to 5.0, 4 to 6.0) to 1.0))
    }

    @Test
    fun `ws 31 two both negative multipliers`() {
        assertContent(listOf(1 to -5.0, 2 to -8.0),
            ws(idea(1 to 1.0, 2 to 2.0) to -1.0, idea(1 to 2.0, 2 to 3.0) to -2.0))
    }

    @Test
    fun `ws 32 two one negative one positive no cancellation`() {
        assertContent(listOf(1 to 1.0, 2 to 1.0),
            ws(idea(1 to 3.0, 2 to 4.0) to 1.0, idea(1 to 2.0, 2 to 3.0) to -1.0))
    }

    @Test
    fun `ws 33 two large contiguous`() {
        val a = IntDoubleEntangledArray((0 until 400).map { it to 2.0 }, allowZero = false)
        val b = IntDoubleEntangledArray((0 until 400).map { it to 3.0 }, allowZero = false)
        val r = ws(a to 1.0, b to 1.0)
        Assertions.assertEquals(400, r.size)
        assertContent((0 until 400).map { it to 5.0 }, r)
    }

    @Test
    fun `ws 34 two near-disjoint contiguous (ratio close to one) routes to merge`() {
        // a=[0..99], b=[1..100]: overlap 99, span+1=101, s=200, ratio ~0.505... wait heavy overlap
        val a = IntDoubleEntangledArray((0 until 100).map { it to 1.0 }, allowZero = false)
        val b = IntDoubleEntangledArray((50 until 150).map { it to 1.0 }, allowZero = false)
        val expected = (0 until 150).map { it to (if (it in 50 until 100) 2.0 else 1.0) }
        assertContent(expected, ws(a to 1.0, b to 1.0))
    }

    @Test
    fun `ws 35 two arrays where b strictly above a`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0, 100 to 30.0, 200 to 40.0),
            ws(idea(1 to 1.0, 2 to 2.0) to 1.0, idea(100 to 3.0, 200 to 4.0) to 10.0))
    }

    // ---- n >= 3 (denseN + mergeN) -------------------------------------------------------

    @Test
    fun `ws 36 three fully overlapping`() {
        assertContent(listOf(1 to 8.0, 2 to 11.0),
            ws(idea(1 to 1.0, 2 to 2.0) to 1.0, idea(1 to 2.0, 2 to 3.0) to 2.0, idea(1 to 1.0, 2 to 1.0) to 3.0))
    }

    @Test
    fun `ws 37 three chained partial overlap`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 2.0, 4 to 1.0),
            ws(idea(1 to 1.0, 2 to 1.0) to 1.0, idea(2 to 1.0, 3 to 1.0) to 1.0, idea(3 to 1.0, 4 to 1.0) to 1.0))
    }

    @Test
    fun `ws 38 three disjoint`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0),
            ws(idea(1 to 1.0) to 1.0, idea(2 to 2.0) to 1.0, idea(3 to 3.0) to 1.0))
    }

    @Test
    fun `ws 39 three full cancellation is EMPTY`() {
        val a = idea(1 to 2.0, 2 to 3.0, 3 to 4.0)
        assertEmpty(ws(a to 1.0, a to 1.0, a to -2.0))
    }

    @Test
    fun `ws 40 three partial cancellation`() {
        assertContent(listOf(3 to 3.0),
            ws(idea(1 to 2.0, 2 to 3.0, 3 to 4.0) to 1.0,
               idea(1 to 1.0, 2 to 3.0, 3 to 1.0) to -1.0,
               idea(1 to 1.0) to -1.0))
    }

    @Test
    fun `ws 41 three spread take merge path`() {
        assertContent(listOf(10 to 5.0, 500 to 1.0, 9000 to 1.0, 100000 to 5.0),
            ws(idea(10 to 1.0, 100000 to 2.0) to 1.0, idea(500 to 1.0, 100000 to 3.0) to 1.0, idea(10 to 4.0, 9000 to 1.0) to 1.0))
    }

    @Test
    fun `ws 42 three contiguous with holes`() {
        assertContent(listOf(0 to 2.0, 1 to 3.0, 5 to 5.0),
            ws(idea(0 to 1.0, 1 to 2.0, 5 to 3.0) to 1.0, idea(0 to 1.0, 5 to 1.0) to 1.0, idea(1 to 1.0, 5 to 1.0) to 1.0))
    }

    @Test
    fun `ws 43 five arrays`() {
        val arrs = (0 until 5).map { idea(it to 1.0, it + 1 to 1.0) }
        assertContent(listOf(0 to 1.0, 1 to 2.0, 2 to 2.0, 3 to 2.0, 4 to 2.0, 5 to 1.0),
            IntDoubleEntangledArray.weightedSum(arrs.map { it to 1.0 }))
    }

    @Test
    fun `ws 44 ten identical arrays equal times ten`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        val sum = IntDoubleEntangledArray.weightedSum(List(10) { a to 1.0 })
        assertContent(listOf(1 to 10.0, 2 to 20.0, 3 to 30.0), sum)
    }

    @Test
    fun `ws 45 effective three after filtering empty and zero-multiplier padding`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 2.0, 4 to 1.0),
            ws(idea(1 to 1.0, 2 to 1.0) to 1.0, idea(9 to 9.0) to 0.0, idea(2 to 1.0, 3 to 1.0) to 1.0,
               EMPTY to 5.0, idea(3 to 1.0, 4 to 1.0) to 1.0))
    }

    @Test
    fun `ws 46 three duplicate same array`() {
        val a = idea(2 to 3.0, 4 to 5.0)
        assertContent(listOf(2 to 9.0, 4 to 15.0), ws(a to 1.0, a to 1.0, a to 1.0))
    }

    @Test
    fun `ws 47 three mixed sign multipliers`() {
        assertContent(listOf(1 to 4.0, 2 to 1.0, 3 to -3.0),
            ws(idea(1 to 2.0, 2 to 2.0, 3 to 1.0) to 2.0, idea(2 to 3.0) to -1.0, idea(3 to 5.0) to -1.0))
    }

    @Test
    fun `ws 48 four spread arrays`() {
        assertContent(listOf(1 to 1.0, 100 to 1.0, 10000 to 1.0, 1000000 to 1.0),
            ws(idea(1 to 1.0) to 1.0, idea(100 to 1.0) to 1.0, idea(10000 to 1.0) to 1.0, idea(1000000 to 1.0) to 1.0))
    }

    // ---- algebraic properties -----------------------------------------------------------

    @Test
    fun `ws 49 commutative in term order`() {
        val a = idea(1 to 2.0, 3 to 4.0); val b = idea(2 to 5.0, 3 to 6.0)
        val ab = ws(a to 2.0, b to 3.0); val ba = ws(b to 3.0, a to 2.0)
        Assertions.assertSame(EMPTY, ws(ab to 1.0, ba to -1.0)); Assertions.assertEquals(ab.size, ba.size)
    }

    @Test
    fun `ws 50 single term equals scalar times`() {
        val a = idea(1 to 2.0, 2 to 3.0, 3 to 4.0)
        Assertions.assertSame(EMPTY, ws(ws(a to 3.0) to 1.0, a.times(3.0) to -1.0))
    }

    @Test
    fun `ws 51 self difference is EMPTY`() {
        val a = idea(1 to 7.0, 4 to 9.0, 8 to 11.0)
        assertEmpty(ws(a to 1.0, a to -1.0))
    }

    @Test
    fun `ws 52 self sum equals times two`() {
        val a = idea(1 to 7.0, 4 to 9.0)
        Assertions.assertSame(EMPTY, ws(ws(a to 1.0, a to 1.0) to 1.0, a.times(2.0) to -1.0))
    }

    @Test
    fun `ws 53 linearity of multiplier`() {
        val a = idea(1 to 2.0, 2 to 3.0); val b = idea(2 to 4.0, 3 to 5.0)
        val scaledSum = ws(a to 2.0, b to 2.0)
        val sumScaled = ws(a to 1.0, b to 1.0).times(2.0)
        Assertions.assertSame(EMPTY, ws(scaledSum to 1.0, sumScaled to -1.0))
    }

    @Test
    fun `ws 54 associativity with unit multipliers`() {
        val a = idea(1 to 1.0, 2 to 2.0); val b = idea(2 to 3.0, 3 to 4.0); val c = idea(3 to 5.0, 4 to 6.0)
        val all = ws(a to 1.0, b to 1.0, c to 1.0)
        val nested = ws(ws(a to 1.0, b to 1.0) to 1.0, c to 1.0)
        Assertions.assertSame(EMPTY, ws(all to 1.0, nested to -1.0)); Assertions.assertEquals(all.size, nested.size)
    }

    @Test
    fun `ws 55 adding empty leaves content unchanged`() {
        val a = idea(1 to 2.0, 2 to 3.0)
        Assertions.assertSame(EMPTY, ws(ws(a to 1.0, EMPTY to 7.0) to 1.0, a to -1.0))
    }

    // ---- edge / robustness ---------------------------------------------------------------

    @Test
    fun `ws 56 extreme level span uses merge without overflow`() {
        assertContent(listOf(Int.MIN_VALUE to 2.0, Int.MAX_VALUE to 3.0),
            ws(idea(Int.MIN_VALUE to 1.0, Int.MAX_VALUE to 1.0) to 1.0,
               idea(Int.MIN_VALUE to 1.0, Int.MAX_VALUE to 2.0) to 1.0))
    }

    @Test
    fun `ws 57 level Int MAX VALUE handled by merge`() {
        assertContent(listOf(0 to 1.0, Int.MAX_VALUE to 3.0),
            ws(idea(0 to 1.0, Int.MAX_VALUE to 1.0) to 1.0, idea(Int.MAX_VALUE to 2.0) to 1.0))
    }

    @Test
    fun `ws 58 level Int MIN VALUE handled`() {
        assertContent(listOf(Int.MIN_VALUE to 3.0, 0 to 1.0),
            ws(idea(Int.MIN_VALUE to 1.0, 0 to 1.0) to 1.0, idea(Int.MIN_VALUE to 2.0) to 1.0))
    }

    @Test
    fun `ws 59 result of weightedSum can be summed again`() {
        val a = idea(1 to 1.0, 2 to 2.0); val b = idea(2 to 3.0, 3 to 4.0)
        val first = ws(a to 1.0, b to 1.0)
        assertContent(listOf(1 to 1.0, 2 to 8.0, 3 to 8.0), ws(first to 1.0, b to 1.0))
    }

    @Test
    fun `ws 60 dense result endpoints`() {
        val r = ws(idea(5 to 1.0, 6 to 1.0, 7 to 1.0) to 1.0, idea(6 to 1.0, 7 to 1.0, 8 to 1.0) to 1.0)
        Assertions.assertEquals(5, r.firstLevel); Assertions.assertEquals(8, r.lastLevel); Assertions.assertEquals(
            4,
            r.size
        )
    }

    // ---- randomized cross-checks (each method is one test, many cases) -------------------
    //
    // The arrays are built from known integer-valued pairs, and canonicalisation (sort / fuse / drop
    // zero) preserves each level's sum, so the reference can be computed straight from the raw input
    // pairs (no need to read the array back). Integer quantities + integer multipliers keep every sum
    // bit-exact regardless of order, so the content cancellation in assertContent is exact.

    @Test
    fun `ws 61 randomized two-array matches reference`() {
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
    fun `ws 62 randomized N-array matches reference`() {
        val rnd = Random(2)
        repeat(400) {
            val spread = rnd.nextInt(3) == 0
            val n = rnd.nextInt(3, 7)
            val pairsAndMul = (0 until n).map { randomPairs(rnd, spread) to rnd.nextInt(-3, 4).toDouble() }
            val terms = pairsAndMul.map { build(it.first) to it.second }
            assertContent(reference(pairsAndMul), IntDoubleEntangledArray.weightedSum(terms))
        }
    }

    @Test
    fun `ws 63 randomized holey-dense matches reference`() {
        val rnd = Random(3)
        repeat(400) {
            val n = rnd.nextInt(2, 6)
            // small gaps -> dense with holes, occasionally pushed to the merge path
            val pairsAndMul = (0 until n).map { randomPairs(rnd, spread = false, gap = 3) to rnd.nextInt(-2, 3).toDouble() }
            val terms = pairsAndMul.map { build(it.first) to it.second }
            assertContent(reference(pairsAndMul), IntDoubleEntangledArray.weightedSum(terms))
        }
    }

    /** Builds an [IntDoubleEntangledArray] from `pairs` (allowing zeros so the merge/dense zero-drop is exercised). */
    private fun build(pairs: List<Pair<Int, Double>>) = IntDoubleEntangledArray(pairs, allowZero = true)

    /** Random ascending pairs with integer quantities (some zero), keeping reference sums bit-exact. */
    private fun randomPairs(rnd: Random, spread: Boolean, gap: Int = if (spread) 30 else 1): List<Pair<Int, Double>> {
        val size = rnd.nextInt(0, 20)
        var level = rnd.nextInt(-5, 5)
        val pairs = ArrayList<Pair<Int, Double>>(size)
        for (k in 0 until size) {
            level += 1 + rnd.nextInt(gap)
            val q = if (rnd.nextDouble() < 0.2) 0.0 else rnd.nextInt(-20, 21).toDouble()
            pairs.add(level to q)
        }
        return pairs
    }

    /** Reference weighted sum computed directly from the raw input pairs: combine, scale, drop zeros, ascending. */
    private fun reference(terms: List<Pair<List<Pair<Int, Double>>, Double>>): List<Pair<Int, Double>> {
        val acc = sortedMapOf<Int, Double>()
        for ((pairs, mult) in terms) {
            if (mult == 0.0) continue
            for ((lvl, q) in pairs) acc[lvl] = (acc[lvl] ?: 0.0) + q * mult
        }
        return acc.entries.filter { it.value != 0.0 }.map { it.key to it.value }
    }

    // =====================================================================================
    // cumulativeSet  — left-to-right overlay A.set(B).set(C): last writer wins, zeros dropped
    // (50+ tests)
    // =====================================================================================

    private fun cs(vararg arrays: IntDoubleEntangledArray) =
        IntDoubleEntangledArray.cumulativeSet(arrays.toList())

    // ---- degenerate / filtering (n == 0) ------------------------------------------------

    @Test
    fun `cs 01 empty list is EMPTY`() { assertEmpty(cs()) }

    @Test
    fun `cs 02 single empty array is EMPTY`() { assertEmpty(cs(EMPTY)) }

    @Test
    fun `cs 03 all empty arrays is EMPTY`() { assertEmpty(cs(EMPTY, EMPTY, EMPTY)) }

    // ---- n == 1 (input returned unchanged, NOT zero-dropped) ----------------------------

    @Test
    fun `cs 04 single array returns the same instance`() {
        val a = idea(1 to 2.0, 5 to 3.0)
        Assertions.assertSame(a, cs(a))
    }

    @Test
    fun `cs 05 single non-empty among empties returns the same instance`() {
        val a = idea(2 to 5.0)
        Assertions.assertSame(a, cs(EMPTY, EMPTY, a, EMPTY))
    }

    @Test
    fun `cs 06 single array content unchanged`() {
        val a = idea(1 to 2.0, 5 to 3.0, 9 to 4.0)
        assertContent(listOf(1 to 2.0, 5 to 3.0, 9 to 4.0), cs(a))
    }

    @Test
    fun `cs 07 single array with zeros drops them (canonical like other arities)`() {
        assertContent(listOf(2 to 5.0), cs(idea(1 to 0.0, 2 to 5.0, 3 to 0.0, allowZero = true)))
        // an all-zero single input collapses to EMPTY
        assertEmpty(cs(idea(1 to 0.0, 2 to 0.0, allowZero = true)))
    }

    // ---- n == 2, dense path -------------------------------------------------------------

    @Test
    fun `cs 08 two disjoint adjacent levels union`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 4.0),
            cs(idea(1 to 1.0, 2 to 2.0), idea(3 to 3.0, 4 to 4.0)))
    }

    @Test
    fun `cs 09 two full overlap replaces all values`() {
        assertContent(listOf(1 to 10.0, 2 to 20.0, 3 to 30.0),
            cs(idea(1 to 1.0, 2 to 2.0, 3 to 3.0), idea(1 to 10.0, 2 to 20.0, 3 to 30.0)))
    }

    @Test
    fun `cs 10 two partial overlap shared replaced unique kept`() {
        // 1: only a=10 ; 2: b wins=99 ; 3: only b=30
        assertContent(listOf(1 to 10.0, 2 to 99.0, 3 to 30.0),
            cs(idea(1 to 10.0, 2 to 20.0), idea(2 to 99.0, 3 to 30.0)))
    }

    @Test
    fun `cs 11 later clears a shared level to zero and it is dropped`() {
        assertContent(listOf(1 to 5.0, 3 to 7.0),
            cs(idea(1 to 5.0, 2 to 6.0, 3 to 7.0), idea(2 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 12 earlier zero overwritten by later nonzero`() {
        assertContent(listOf(5 to 7.0),
            cs(idea(5 to 0.0, allowZero = true), idea(5 to 7.0)))
    }

    @Test
    fun `cs 13 order matters - later wins`() {
        val a = idea(1 to 10.0, 2 to 20.0)
        val b = idea(2 to 99.0, 3 to 30.0)
        assertContent(listOf(1 to 10.0, 2 to 99.0, 3 to 30.0), cs(a, b))
        assertContent(listOf(1 to 10.0, 2 to 20.0, 3 to 30.0), cs(b, a))
    }

    @Test
    fun `cs 14 idempotent - same array twice equals itself`() {
        val a = idea(1 to 3.0, 5 to 7.0, 9 to 11.0)
        assertContent(listOf(1 to 3.0, 5 to 7.0, 9 to 11.0), cs(a, a))
    }

    @Test
    fun `cs 15 two singletons same level last wins`() {
        assertContent(listOf(7 to 5.0), cs(idea(7 to 2.0), idea(7 to 5.0)))
    }

    @Test
    fun `cs 16 two singletons different levels`() {
        assertContent(listOf(3 to 1.0, 8 to 2.0), cs(idea(3 to 1.0), idea(8 to 2.0)))
    }

    @Test
    fun `cs 17 negative levels and quantities`() {
        assertContent(listOf(-5 to -1.0, -1 to -9.0, 3 to -4.0),
            cs(idea(-5 to -1.0, -1 to -2.0), idea(-1 to -9.0, 3 to -4.0)))
    }

    @Test
    fun `cs 18 two all-clear yields EMPTY (dense)`() {
        assertEmpty(cs(idea(1 to 5.0, 2 to 6.0), idea(1 to 0.0, 2 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 19 dense with holes`() {
        // union 0..3 (span 3, s 6) -> dense; 0,3 from b ; 1,2 from a
        assertContent(listOf(0 to 9.0, 1 to 1.0, 2 to 1.0, 3 to 9.0),
            cs(idea(0 to 1.0, 1 to 1.0, 2 to 1.0, 3 to 1.0), idea(0 to 9.0, 3 to 9.0)))
    }

    @Test
    fun `cs 20 endpoints reflect union extremes`() {
        val r = cs(idea(5 to 1.0, 6 to 1.0), idea(2 to 1.0, 9 to 1.0))
        Assertions.assertEquals(2, r.firstLevel); Assertions.assertEquals(9, r.lastLevel); Assertions.assertEquals(
            4,
            r.size
        )
    }

    // ---- n == 2, merge path (spread levels) ---------------------------------------------

    @Test
    fun `cs 21 two spread disjoint union (merge)`() {
        assertContent(listOf(10 to 1.0, 500 to 5.0, 1000 to 3.0, 100000 to 4.0),
            cs(idea(10 to 1.0, 1000 to 3.0), idea(500 to 5.0, 100000 to 4.0)))
    }

    @Test
    fun `cs 22 two spread overlap later wins (merge)`() {
        assertContent(listOf(10 to 9.0, 5000 to 3.0, 999999 to 4.0),
            cs(idea(10 to 2.0, 5000 to 3.0), idea(10 to 9.0, 999999 to 4.0)))
    }

    @Test
    fun `cs 23 two spread later clears shared (merge)`() {
        assertContent(listOf(10 to 5.0),
            cs(idea(10 to 5.0, 100000 to 7.0), idea(100000 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 24 two spread all-clear yields EMPTY (merge)`() {
        assertEmpty(cs(idea(10 to 5.0, 100000 to 6.0),
            idea(10 to 0.0, 100000 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 25 extreme level span no overflow (merge)`() {
        assertContent(listOf(Int.MIN_VALUE to 1.0, Int.MAX_VALUE to 5.0),
            cs(idea(Int.MIN_VALUE to 1.0, Int.MAX_VALUE to 1.0), idea(Int.MAX_VALUE to 5.0)))
    }

    @Test
    fun `cs 26 idempotent on spread array (merge)`() {
        val a = idea(10 to 3.0, 100000 to 7.0)
        assertContent(listOf(10 to 3.0, 100000 to 7.0), cs(a, a))
    }

    // ---- n >= 3, dense path -------------------------------------------------------------

    @Test
    fun `cs 27 three last writer wins per shared level`() {
        // 1: a=1 ; 2: b=2 ; 3: c=3
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0),
            cs(idea(1 to 1.0, 2 to 1.0, 3 to 1.0), idea(2 to 2.0, 3 to 2.0), idea(3 to 3.0)))
    }

    @Test
    fun `cs 28 three chained partial overlap`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 4.0),
            cs(idea(1 to 1.0, 2 to 1.0), idea(2 to 2.0, 3 to 2.0), idea(3 to 3.0, 4 to 4.0)))
    }

    @Test
    fun `cs 29 three disjoint`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0),
            cs(idea(1 to 1.0), idea(2 to 2.0), idea(3 to 3.0)))
    }

    @Test
    fun `cs 30 three - last array clears a level`() {
        // 1: a=5 ; 2: b=7 ; 3: b=7 then c clears -> dropped
        assertContent(listOf(1 to 5.0, 2 to 7.0),
            cs(idea(1 to 5.0, 2 to 5.0), idea(2 to 7.0, 3 to 7.0), idea(3 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 31 three - clear then reset keeps the last nonzero`() {
        assertContent(listOf(5 to 4.0),
            cs(idea(5 to 9.0), idea(5 to 0.0, allowZero = true), idea(5 to 4.0)))
    }

    @Test
    fun `cs 32 three - level in all arrays last is zero is dropped`() {
        assertEmpty(cs(idea(5 to 1.0), idea(5 to 2.0), idea(5 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 33 many identical contiguous arrays equal a single one (dense)`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0),
            IntDoubleEntangledArray.cumulativeSet(List(8) { a }))
    }

    @Test
    fun `cs 34 three dense with holes mixed precedence`() {
        // 0: only a=1 ; 1: a then c -> c=3 ; 5: a,b,c -> c=3
        assertContent(listOf(0 to 1.0, 1 to 3.0, 5 to 3.0),
            cs(idea(0 to 1.0, 1 to 1.0, 5 to 1.0), idea(5 to 2.0), idea(1 to 3.0, 5 to 3.0)))
    }

    // ---- n >= 3, merge path (spread levels, exercises the heap) --------------------------

    @Test
    fun `cs 35 three spread disjoint (merge)`() {
        assertContent(listOf(10 to 1.0, 5000 to 2.0, 100000 to 3.0),
            cs(idea(10 to 1.0), idea(5000 to 2.0), idea(100000 to 3.0)))
    }

    @Test
    fun `cs 36 three spread overlap last writer (merge)`() {
        // 10: b=2 ; 5000: b=2 ; 100000: c=3
        assertContent(listOf(10 to 2.0, 5000 to 2.0, 100000 to 3.0),
            cs(idea(10 to 1.0, 100000 to 1.0), idea(10 to 2.0, 5000 to 2.0), idea(100000 to 3.0)))
    }

    @Test
    fun `cs 37 three spread clear all yields EMPTY (merge)`() {
        assertEmpty(cs(
            idea(1 to 1.0, 100000 to 1.0),
            idea(1 to 2.0, 50000 to 2.0),
            idea(1 to 0.0, 50000 to 0.0, 100000 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs 38 three extreme span (merge)`() {
        assertContent(listOf(Int.MIN_VALUE to 1.0, 0 to 2.0, Int.MAX_VALUE to 3.0),
            cs(idea(Int.MIN_VALUE to 1.0, 0 to 1.0, Int.MAX_VALUE to 1.0),
               idea(0 to 2.0), idea(Int.MAX_VALUE to 3.0)))
    }

    @Test
    fun `cs 39 four spread arrays union`() {
        assertContent(listOf(1 to 1.0, 100 to 2.0, 10000 to 3.0, 1000000 to 4.0),
            cs(idea(1 to 1.0), idea(100 to 2.0), idea(10000 to 3.0), idea(1000000 to 4.0)))
    }

    @Test
    fun `cs 40 many identical spread arrays equal a single one (merge tie-drain)`() {
        val a = idea(10 to 1.0, 100000 to 2.0)
        assertContent(listOf(10 to 1.0, 100000 to 2.0),
            IntDoubleEntangledArray.cumulativeSet(List(6) { a }))
    }

    @Test
    fun `cs 41 ten arrays distinct levels union`() {
        val arrs = (0 until 10).map { idea(it to (it + 1).toDouble()) }
        assertContent((0 until 10).map { it to (it + 1).toDouble() },
            IntDoubleEntangledArray.cumulativeSet(arrs))
    }

    // ---- filtering + compaction (empties between real inputs) ----------------------------

    @Test
    fun `cs 42 interspersed empties spread forces merge with compaction`() {
        // 3 real inputs among empties -> cumulativeSetN compacts the array, spread -> heap merge
        val a = idea(10 to 1.0, 100000 to 2.0)
        val b = idea(500 to 3.0, 100000 to 9.0)
        val c = idea(10 to 7.0, 9000 to 5.0)
        assertContent(listOf(10 to 7.0, 500 to 3.0, 9000 to 5.0, 100000 to 9.0),
            cs(EMPTY, a, EMPTY, b, EMPTY, c, EMPTY))
    }

    @Test
    fun `cs 43 empties reducing to two dispatches to set2`() {
        assertContent(listOf(1 to 2.0, 3 to 9.0, 5 to 6.0),
            cs(EMPTY, idea(1 to 2.0, 3 to 4.0), EMPTY, idea(3 to 9.0, 5 to 6.0)))
    }

    @Test
    fun `cs 44 empties reducing to one returns the same instance`() {
        val a = idea(2 to 5.0, 7 to 8.0)
        Assertions.assertSame(a, cs(EMPTY, EMPTY, a, EMPTY))
    }

    @Test
    fun `cs 45 interspersed empties contiguous (dense compaction)`() {
        val a = idea(1 to 1.0, 2 to 1.0, 3 to 1.0)
        val b = idea(2 to 5.0, 3 to 5.0)
        val c = idea(3 to 9.0)
        assertContent(listOf(1 to 1.0, 2 to 5.0, 3 to 9.0),
            cs(EMPTY, a, EMPTY, b, c, EMPTY))
    }

    // ---- larger / re-use / properties ---------------------------------------------------

    @Test
    fun `cs 46 large contiguous overlay - last wins (dense)`() {
        val a = IntDoubleEntangledArray((0 until 400).map { it to 1.0 }, allowZero = false)
        val b = IntDoubleEntangledArray((0 until 400).map { it to 2.0 }, allowZero = false)
        assertContent((0 until 400).map { it to 2.0 }, cs(a, b))
    }

    @Test
    fun `cs 47 large spread overlay - last wins (merge)`() {
        val a = IntDoubleEntangledArray((0 until 300).map { (it * 1000) to 1.0 }, allowZero = false)
        val b = IntDoubleEntangledArray((0 until 300).map { (it * 1000) to 2.0 }, allowZero = false)
        assertContent((0 until 300).map { (it * 1000) to 2.0 }, cs(a, b))
    }

    @Test
    fun `cs 48 result can be overlaid again`() {
        val first = cs(idea(1 to 1.0, 2 to 2.0), idea(2 to 5.0, 3 to 3.0)) // {1:1, 2:5, 3:3}
        assertContent(listOf(1 to 1.0, 2 to 5.0, 3 to 9.0, 4 to 4.0),
            cs(first, idea(3 to 9.0, 4 to 4.0)))
    }

    @Test
    fun `cs 49 equals left-deep pairwise fold A set B set C`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        val b = idea(2 to 9.0, 4 to 4.0)
        val c = idea(3 to 7.0, 4 to 8.0, 5 to 5.0)
        val all = cs(a, b, c)
        val fold = cs(cs(a, b), c)
        Assertions.assertSame(EMPTY, ws(all to 1.0, fold to -1.0))
        Assertions.assertEquals(all.size, fold.size)
    }

    @Test
    fun `cs 50 not commutative in input order`() {
        val a = idea(1 to 1.0, 5 to 1.0)
        val b = idea(5 to 2.0)
        val c = idea(5 to 3.0)
        assertContent(listOf(1 to 1.0, 5 to 3.0), cs(a, b, c)) // c last on level 5
        assertContent(listOf(1 to 1.0, 5 to 1.0), cs(b, c, a)) // a last on level 5
    }

    // ---- randomized cross-checks against a direct overlay reference ----------------------
    //
    // Inputs use unique ascending levels (no constructor fusing) and integer quantities (some zero),
    // so the overlay reference can be read straight from the raw pairs and the cancellation in
    // assertContent stays bit-exact. Every arity drops zeros, so the result is always canonical and
    // matches the reference (which filters zeros) directly.

    @Test
    fun `cs 51 randomized two-array matches reference`() {
        val rnd = Random(11)
        repeat(400) {
            val spread = rnd.nextBoolean()
            val a = setPairs(rnd, spread); val b = setPairs(rnd, spread)
            assertContent(referenceSet(listOf(a, b)), cs(build(a), build(b)))
        }
    }

    @Test
    fun `cs 52 randomized N-array contiguous matches reference`() {
        val rnd = Random(12)
        repeat(400) {
            val n = rnd.nextInt(3, 7)
            val arrays = (0 until n).map { setPairs(rnd, spread = false) }
            assertContent(referenceSet(arrays),
                IntDoubleEntangledArray.cumulativeSet(arrays.map { build(it) }))
        }
    }

    @Test
    fun `cs 53 randomized N-array spread matches reference`() {
        val rnd = Random(13)
        repeat(400) {
            val n = rnd.nextInt(3, 8)
            val arrays = (0 until n).map { setPairs(rnd, spread = true) }
            assertContent(referenceSet(arrays),
                IntDoubleEntangledArray.cumulativeSet(arrays.map { build(it) }))
        }
    }

    @Test
    fun `cs 54 randomized interspersed empties matches reference`() {
        val rnd = Random(14)
        repeat(400) {
            val spread = rnd.nextBoolean()
            val n = rnd.nextInt(2, 7)
            val arrays = (0 until n).map { setPairs(rnd, spread) }
            val terms = ArrayList<IntDoubleEntangledArray>()
            for (a in arrays) { terms.add(EMPTY); terms.add(build(a)) }
            terms.add(EMPTY)
            assertContent(referenceSet(arrays), IntDoubleEntangledArray.cumulativeSet(terms))
        }
    }

    @Test
    fun `cs 55 randomized holey matches reference`() {
        val rnd = Random(15)
        repeat(400) {
            val n = rnd.nextInt(2, 6)
            val arrays = (0 until n).map { setPairs(rnd, spread = false, gap = 4) }
            assertContent(referenceSet(arrays),
                IntDoubleEntangledArray.cumulativeSet(arrays.map { build(it) }))
        }
    }

    // ---- zero removal, one case per code path -------------------------------------------
    //
    // Inputs are built with allowZero = true so the zero entries actually reach the overlay; every
    // path must drop them. assertContent's size check is what pins this down (a kept zero would make
    // actual.size exceed the zero-free expected size). Endpoints are asserted where a boundary level
    // (the result min or max) is the one being dropped.

    @Test
    fun `cs zero 01 set1 drops interior zeros`() {
        // single input -> cumulativeSet1
        assertContent(listOf(1 to 3.0, 3 to 7.0, 5 to 9.0),
            cs(idea(1 to 3.0, 2 to 0.0, 3 to 7.0, 4 to 0.0, 5 to 9.0, allowZero = true)))
    }

    @Test
    fun `cs zero 02 set1 drops leading and trailing zeros and shifts endpoints`() {
        val r = cs(idea(1 to 0.0, 2 to 0.0, 3 to 7.0, 4 to 0.0, allowZero = true))
        assertContent(listOf(3 to 7.0), r)
        Assertions.assertEquals(3, r.firstLevel); Assertions.assertEquals(3, r.lastLevel); Assertions.assertEquals(
            1,
            r.size
        )
    }

    @Test
    fun `cs zero 03 set1 large compaction drops a single middle zero`() {
        val a = IntDoubleEntangledArray((0 until 100).map { it to (if (it == 50) 0.0 else 1.0) }, allowZero = true)
        val r = cs(a)
        assertContent((0 until 100).filter { it != 50 }.map { it to 1.0 }, r)
        Assertions.assertEquals(99, r.size); Assertions.assertEquals(0, r.firstLevel); Assertions.assertEquals(
            99,
            r.lastLevel
        )
    }

    @Test
    fun `cs zero 04 denseSet2 drops pre-existing input zeros`() {
        assertContent(listOf(2 to 5.0, 4 to 6.0),
            cs(idea(1 to 0.0, 2 to 5.0, allowZero = true), idea(3 to 0.0, 4 to 6.0, allowZero = true)))
    }

    @Test
    fun `cs zero 05 denseSet2 clears min and max levels endpoints recompute`() {
        val r = cs(idea(1 to 5.0, 2 to 6.0, 3 to 7.0), idea(1 to 0.0, 3 to 0.0, allowZero = true))
        assertContent(listOf(2 to 6.0), r)
        Assertions.assertEquals(2, r.firstLevel); Assertions.assertEquals(2, r.lastLevel); Assertions.assertEquals(
            1,
            r.size
        )
    }

    @Test
    fun `cs zero 06 mergeSet2 drops pre-existing input zeros (spread)`() {
        assertContent(listOf(100000 to 7.0, 999999 to 4.0),
            cs(idea(10 to 0.0, 100000 to 7.0, allowZero = true),
               idea(500 to 0.0, 999999 to 4.0, allowZero = true)))
    }

    @Test
    fun `cs zero 07 mergeSet2 clears min and max levels endpoints recompute (spread)`() {
        val r = cs(idea(10 to 5.0, 5000 to 6.0, 100000 to 7.0),
                   idea(10 to 0.0, 100000 to 0.0, allowZero = true))
        assertContent(listOf(5000 to 6.0), r)
        Assertions.assertEquals(5000, r.firstLevel); Assertions.assertEquals(
            5000,
            r.lastLevel
        ); Assertions.assertEquals(1, r.size)
    }

    @Test
    fun `cs zero 08 denseSetN drops zeros from inputs and overwrites-to-zero`() {
        // 1: a=0 drop ; 2: a=5 ; 3: a then b=0 drop ; 4: b then c=0 drop ; 5: c=5
        assertContent(listOf(2 to 5.0, 5 to 5.0),
            cs(idea(1 to 0.0, 2 to 5.0, 3 to 3.0, allowZero = true),
               idea(3 to 0.0, 4 to 4.0, allowZero = true),
               idea(4 to 0.0, 5 to 5.0, allowZero = true)))
    }

    @Test
    fun `cs zero 09 denseSetN clears min and max levels endpoints recompute`() {
        val r = cs(idea(1 to 5.0, 2 to 6.0, 3 to 7.0),
                   idea(1 to 0.0, allowZero = true), idea(3 to 0.0, allowZero = true))
        assertContent(listOf(2 to 6.0), r)
        Assertions.assertEquals(2, r.firstLevel); Assertions.assertEquals(2, r.lastLevel); Assertions.assertEquals(
            1,
            r.size
        )
    }

    @Test
    fun `cs zero 10 mergeSetN drops zeros from inputs and overwrites-to-zero (spread)`() {
        // 10: a=0 then c=7 -> 7 ; 5000: b=2 ; 9000: c=0 drop ; 100000: a=1 then b=0 -> drop
        assertContent(listOf(10 to 7.0, 5000 to 2.0),
            cs(idea(10 to 0.0, 100000 to 1.0, allowZero = true),
               idea(5000 to 2.0, 100000 to 0.0, allowZero = true),
               idea(10 to 7.0, 9000 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs zero 11 mergeSetN clears min and max levels endpoints recompute (spread)`() {
        val r = cs(idea(10 to 5.0, 5000 to 6.0, 100000 to 7.0),
                   idea(10 to 0.0, allowZero = true), idea(100000 to 0.0, allowZero = true))
        assertContent(listOf(5000 to 6.0), r)
        Assertions.assertEquals(5000, r.firstLevel); Assertions.assertEquals(
            5000,
            r.lastLevel
        ); Assertions.assertEquals(1, r.size)
    }

    @Test
    fun `cs zero 12 every level cancels to zero yields EMPTY across paths`() {
        // dense2, merge2, denseN, mergeN must each collapse to the EMPTY singleton
        assertEmpty(cs(idea(1 to 5.0, 2 to 6.0), idea(1 to 0.0, 2 to 0.0, allowZero = true)))
        assertEmpty(cs(idea(10 to 5.0, 100000 to 6.0), idea(10 to 0.0, 100000 to 0.0, allowZero = true)))
        assertEmpty(cs(idea(1 to 5.0), idea(2 to 6.0), idea(1 to 0.0, 2 to 0.0, allowZero = true)))
        assertEmpty(cs(idea(10 to 5.0), idea(100000 to 6.0),
            idea(10 to 0.0, 100000 to 0.0, allowZero = true)))
    }

    @Test
    fun `cs zero 13 randomized inputs riddled with zeros stay canonical`() {
        // ~50% of entries are zero, across dense and spread, single and many inputs
        val rnd = Random(99)
        repeat(400) {
            val spread = rnd.nextBoolean()
            val n = rnd.nextInt(1, 7)
            val arrays = (0 until n).map { zeroHeavyPairs(rnd, spread) }
            val result = IntDoubleEntangledArray.cumulativeSet(arrays.map { build(it) })
            assertContent(referenceSet(arrays), result)
        }
    }

    /** Like [setPairs] but ~half the quantities are zero, to stress the zero-drop on every path. */
    private fun zeroHeavyPairs(rnd: Random, spread: Boolean): List<Pair<Int, Double>> {
        val gap = if (spread) 40 else 1
        val size = rnd.nextInt(1, 20)
        var level = rnd.nextInt(-5, 5)
        val pairs = ArrayList<Pair<Int, Double>>(size)
        for (k in 0 until size) {
            level += 1 + rnd.nextInt(gap)
            val q = if (rnd.nextDouble() < 0.5) 0.0 else rnd.nextInt(-20, 21).toDouble()
            pairs.add(level to q)
        }
        return pairs
    }

    /** Random ascending pairs with unique levels and at least one entry (some quantities zero). */
    private fun setPairs(rnd: Random, spread: Boolean, gap: Int = if (spread) 40 else 1): List<Pair<Int, Double>> {
        val size = rnd.nextInt(1, 20)
        var level = rnd.nextInt(-5, 5)
        val pairs = ArrayList<Pair<Int, Double>>(size)
        for (k in 0 until size) {
            level += 1 + rnd.nextInt(gap)
            val q = if (rnd.nextDouble() < 0.2) 0.0 else rnd.nextInt(-20, 21).toDouble()
            pairs.add(level to q)
        }
        return pairs
    }

    /** Reference overlay: later arrays set each level (last writer wins); zeros dropped; ascending. */
    private fun referenceSet(arrays: List<List<Pair<Int, Double>>>): List<Pair<Int, Double>> {
        val acc = sortedMapOf<Int, Double>()
        for (pairs in arrays) for ((lvl, q) in pairs) acc[lvl] = q
        return acc.entries.filter { it.value != 0.0 }.map { it.key to it.value }
    }

    // =====================================================================================
    // absoluteDiff  — level-by-level |qa - qb|, absent side counts as 0, zeros dropped
    // =====================================================================================

    private fun ad(a: IntDoubleEntangledArray, b: IntDoubleEntangledArray) =
        IntDoubleEntangledArray.absoluteDiff(a, b)

    /** Reference: for every level in either input, |a - b| with an absent side as 0; zeros dropped, ascending. */
    private fun referenceDiff(a: List<Pair<Int, Double>>, b: List<Pair<Int, Double>>): List<Pair<Int, Double>> {
        val ma = a.toMap(); val mb = b.toMap()
        require(ma.size == a.size && mb.size == b.size) { "reference assumes unique levels per side" }
        val levels = (ma.keys + mb.keys).toSortedSet()
        return levels.mapNotNull { l ->
            val q = abs((ma[l] ?: 0.0) - (mb[l] ?: 0.0))
            if (q != 0.0) l to q else null
        }
    }

    // ---- degenerate / empties -----------------------------------------------------------

    @Test
    fun `ad 01 both empty is EMPTY`() { assertEmpty(ad(EMPTY, EMPTY)) }

    @Test
    fun `ad 02 first empty yields absolute values of second`() {
        assertContent(listOf(1 to 2.0, 3 to 4.0), ad(EMPTY, idea(1 to 2.0, 3 to -4.0)))
    }

    @Test
    fun `ad 03 second empty yields absolute values of first`() {
        assertContent(listOf(1 to 2.0, 3 to 4.0), ad(idea(1 to -2.0, 3 to 4.0), EMPTY))
    }

    @Test
    fun `ad 03b empty against all-positive array returns the same instance`() {
        val a = idea(1 to 2.0, 3 to 4.0)
        Assertions.assertSame(a, ad(EMPTY, a))
        Assertions.assertSame(a, ad(a, EMPTY))
    }

    @Test
    fun `ad 03c empty against array with a negative builds a fresh canonical copy`() {
        val a = idea(1 to -2.0, 3 to 4.0)
        val r = ad(EMPTY, a)
        Assertions.assertNotSame(a, r)
        assertContent(listOf(1 to 2.0, 3 to 4.0), r)
    }

    @Test
    fun `ad 03d empty against array with a zero drops it (not the same instance)`() {
        val a = idea(1 to 0.0, 3 to 4.0, allowZero = true)
        val r = ad(a, EMPTY)
        Assertions.assertNotSame(a, r)
        assertContent(listOf(3 to 4.0), r)
    }

    @Test
    fun `ad 03e empty against all-zero array is EMPTY`() {
        assertEmpty(ad(EMPTY, idea(1 to 0.0, 2 to 0.0, allowZero = true)))
    }

    @Test
    fun `ad 04 identical arrays cancel to EMPTY`() {
        val a = idea(1 to 2.0, 2 to 3.0, 3 to 4.0)
        assertEmpty(ad(a, a))
    }

    // ---- overlap / union (dense path) ---------------------------------------------------

    @Test
    fun `ad 05 full overlap differing values`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0),
            ad(idea(1 to 10.0, 2 to 20.0, 3 to 30.0), idea(1 to 9.0, 2 to 18.0, 3 to 27.0)))
    }

    @Test
    fun `ad 06 disjoint adjacent levels union of absolute values`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 4.0),
            ad(idea(1 to 1.0, 2 to 2.0), idea(3 to -3.0, 4 to -4.0)))
    }

    @Test
    fun `ad 07 partial overlap shared subtracts unique kept as magnitude`() {
        // 1: only a=|7| ; 2: |5-2|=3 ; 3: only b=|9|
        assertContent(listOf(1 to 7.0, 2 to 3.0, 3 to 9.0),
            ad(idea(1 to 7.0, 2 to 5.0), idea(2 to 2.0, 3 to -9.0)))
    }

    @Test
    fun `ad 08 shared level with equal values is dropped`() {
        // level 2 equal -> dropped; 1 and 3 survive
        assertContent(listOf(1 to 1.0, 3 to 3.0),
            ad(idea(1 to 1.0, 2 to 5.0, 3 to 3.0), idea(2 to 5.0)))
    }

    @Test
    fun `ad 09 result is always non-negative`() {
        // a smaller than b on every shared level, yet differences come out positive
        assertContent(listOf(1 to 4.0, 2 to 4.0),
            ad(idea(1 to 1.0, 2 to 1.0), idea(1 to 5.0, 2 to 5.0)))
    }

    @Test
    fun `ad 10 symmetric in argument order`() {
        val a = idea(1 to 2.0, 3 to 4.0, 5 to 6.0)
        val b = idea(1 to 9.0, 2 to 1.0, 5 to 6.0)
        val ab = ad(a, b); val ba = ad(b, a)
        Assertions.assertSame(EMPTY, ws(ab to 1.0, ba to -1.0)); Assertions.assertEquals(ab.size, ba.size)
    }

    @Test
    fun `ad 11 dense with holes`() {
        // union 0..3 ; 0 only a ; 1 shared diff ; 2 only b ; 3 shared equal -> dropped
        assertContent(listOf(0 to 1.0, 1 to 4.0, 2 to 2.0),
            ad(idea(0 to 1.0, 1 to 5.0, 3 to 7.0), idea(1 to 1.0, 2 to 2.0, 3 to 7.0)))
    }

    @Test
    fun `ad 12 endpoints reflect surviving extremes`() {
        // min level 1 and max level 9 both cancel -> result spans only the interior
        val r = ad(idea(1 to 5.0, 4 to 1.0, 9 to 5.0), idea(1 to 5.0, 4 to 8.0, 9 to 5.0))
        assertContent(listOf(4 to 7.0), r)
        Assertions.assertEquals(4, r.firstLevel); Assertions.assertEquals(4, r.lastLevel); Assertions.assertEquals(
            1,
            r.size
        )
    }

    // ---- spread levels (forces the long merge tails) ------------------------------------

    @Test
    fun `ad 13 spread disjoint union`() {
        assertContent(listOf(10 to 1.0, 500 to 5.0, 1000 to 3.0, 100000 to 4.0),
            ad(idea(10 to 1.0, 1000 to 3.0), idea(500 to 5.0, 100000 to -4.0)))
    }

    @Test
    fun `ad 14 spread overlap differs and cancels`() {
        // 10: |2-9|=7 ; 5000: only a=3 ; 999999: equal -> dropped
        assertContent(listOf(10 to 7.0, 5000 to 3.0),
            ad(idea(10 to 2.0, 5000 to 3.0, 999999 to 4.0), idea(10 to 9.0, 999999 to 4.0)))
    }

    @Test
    fun `ad 15 extreme level span no overflow`() {
        assertContent(listOf(Int.MIN_VALUE to 1.0, Int.MAX_VALUE to 3.0),
            ad(idea(Int.MIN_VALUE to 1.0, Int.MAX_VALUE to 5.0), idea(Int.MAX_VALUE to 2.0)))
    }

    // ---- zeros in inputs ----------------------------------------------------------------

    @Test
    fun `ad 16 zero entry against absent side is dropped`() {
        // a has a 0.0 at level 2 (kept by allowZero); b lacks it -> |0| dropped
        assertContent(listOf(1 to 3.0),
            ad(idea(1 to 3.0, 2 to 0.0, allowZero = true), EMPTY))
    }

    @Test
    fun `ad 17 zero against nonzero yields the magnitude`() {
        assertContent(listOf(2 to 5.0),
            ad(idea(2 to 0.0, allowZero = true), idea(2 to -5.0)))
    }

    // ---- larger -------------------------------------------------------------------------

    @Test
    fun `ad 18 large contiguous constant difference`() {
        val a = IntDoubleEntangledArray((0 until 400).map { it to 5.0 }, allowZero = false)
        val b = IntDoubleEntangledArray((0 until 400).map { it to 2.0 }, allowZero = false)
        assertContent((0 until 400).map { it to 3.0 }, ad(a, b))
    }

    @Test
    fun `ad 19 result can be diffed again`() {
        val first = ad(idea(1 to 5.0, 2 to 2.0), idea(1 to 1.0, 2 to 8.0)) // {1:4, 2:6}
        assertContent(listOf(1 to 3.0, 2 to 6.0), ad(first, idea(1 to 1.0)))
    }

    // ---- randomized cross-check ---------------------------------------------------------
    //
    // Unique ascending levels and integer quantities (some zero) keep the reference exact: |a - b|
    // of integers is an integer, so the cancellation in assertContent stays bit-exact.

    @Test
    fun `ad 20 randomized matches reference`() {
        val rnd = Random(21)
        repeat(800) {
            val spread = rnd.nextBoolean()
            val a = setPairs(rnd, spread); val b = setPairs(rnd, spread)
            assertContent(referenceDiff(a, b), ad(build(a), build(b)))
        }
    }

    @Test
    fun `ad 21 negative levels`() {
        assertContent(listOf(-5 to 1.0, -3 to 9.0, -1 to 4.0),
            ad(idea(-5 to 1.0, -1 to 2.0), idea(-3 to -9.0, -1 to 6.0)))
    }

    @Test
    fun `ad 22 single element each different level`() {
        assertContent(listOf(3 to 2.0, 8 to 5.0), ad(idea(3 to -2.0), idea(8 to -5.0)))
    }

    @Test
    fun `ad 23 equals first absolute when second empty`() {
        val a = idea(1 to -2.0, 2 to 3.0, 3 to -4.0)
        Assertions.assertSame(EMPTY, ws(ad(a, EMPTY) to 1.0, a.absolute() to -1.0))
    }

    // =====================================================================================
    // absolute  — per-level magnitude, zeros dropped, all-positive input returned as-is
    // =====================================================================================

    /** Reference: |q| at every level with zeros dropped, ascending (inputs use unique levels). */
    private fun referenceAbs(a: List<Pair<Int, Double>>): List<Pair<Int, Double>> {
        require(a.toMap().size == a.size) { "reference assumes unique levels" }
        return a.sortedBy { it.first }
            .mapNotNull { (l, q) -> abs(q).let { if (it != 0.0) l to it else null } }
    }

    // ---- degenerate / empties -----------------------------------------------------------

    @Test
    fun `abs 01 EMPTY returns the EMPTY singleton`() {
        Assertions.assertSame(EMPTY, EMPTY.absolute())
    }

    @Test
    fun `abs 02 empty-constructed array returns EMPTY singleton`() {
        assertEmpty(idea().absolute())
    }

    @Test
    fun `abs 03 all-zero array is EMPTY`() {
        assertEmpty(idea(1 to 0.0, 2 to 0.0, allowZero = true).absolute())
    }

    // ---- all-positive shortcut (same instance) ------------------------------------------

    @Test
    fun `abs 04 all-positive array returns the same instance`() {
        val a = idea(1 to 2.0, 2 to 3.0, 3 to 4.0)
        Assertions.assertSame(a, a.absolute())
    }

    @Test
    fun `abs 05 single positive returns the same instance`() {
        val a = idea(7 to 1.0)
        Assertions.assertSame(a, a.absolute())
    }

    @Test
    fun `abs 06 idempotent - absolute of a result is all-positive so returns same instance`() {
        val once = idea(1 to -2.0, 2 to 3.0).absolute()
        Assertions.assertSame(once, once.absolute())
    }

    // ---- negatives / mixed (fresh canonical copy) ---------------------------------------

    @Test
    fun `abs 07 all-negative array maps to magnitudes`() {
        val a = idea(1 to -2.0, 2 to -3.0, 3 to -4.0)
        val r = a.absolute()
        Assertions.assertNotSame(a, r)
        assertContent(listOf(1 to 2.0, 2 to 3.0, 3 to 4.0), r)
    }

    @Test
    fun `abs 08 mixed signs map to magnitudes`() {
        assertContent(listOf(1 to 2.0, 2 to 3.0, 3 to 4.0),
            idea(1 to -2.0, 2 to 3.0, 3 to -4.0).absolute())
    }

    @Test
    fun `abs 09 single negative maps to its magnitude`() {
        val a = idea(7 to -5.0)
        val r = a.absolute()
        Assertions.assertNotSame(a, r)
        assertContent(listOf(7 to 5.0), r)
    }

    // ---- zero dropping ------------------------------------------------------------------

    @Test
    fun `abs 10 interior zeros dropped not same instance`() {
        val a = idea(1 to -3.0, 2 to 0.0, 3 to 7.0, allowZero = true)
        val r = a.absolute()
        Assertions.assertNotSame(a, r)
        assertContent(listOf(1 to 3.0, 3 to 7.0), r)
    }

    @Test
    fun `abs 11 leading and trailing zeros dropped endpoints recompute`() {
        val r = idea(1 to 0.0, 2 to 0.0, 3 to -7.0, 4 to 0.0, allowZero = true).absolute()
        assertContent(listOf(3 to 7.0), r)
        Assertions.assertEquals(3, r.firstLevel); Assertions.assertEquals(3, r.lastLevel); Assertions.assertEquals(
            1,
            r.size
        )
    }

    // ---- larger -------------------------------------------------------------------------

    @Test
    fun `abs 12 large array with scattered negatives and zeros`() {
        val a = IntDoubleEntangledArray(
            (0 until 300).map { it to (if (it % 7 == 0) 0.0 else if (it % 2 == 0) -it.toDouble() else it.toDouble()) },
            allowZero = true,
        )
        assertContent((0 until 300).filter { it % 7 != 0 }.map { it to it.toDouble() }, a.absolute())
    }

    @Test
    fun `abs 13 large all-positive array returns same instance`() {
        val a = IntDoubleEntangledArray((0 until 300).map { it to (it + 1).toDouble() }, allowZero = false)
        Assertions.assertSame(a, a.absolute())
    }

    // ---- randomized cross-check ---------------------------------------------------------

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

    // ---- degenerate n (non-positive -> EMPTY singleton) ---------------------------------

    @Test
    fun `head 01 zero count is EMPTY singleton`() {
        assertEmpty(idea(1 to 1.0, 2 to 2.0, 3 to 3.0).head(0))
    }

    @Test
    fun `head 02 negative count is EMPTY singleton`() {
        assertEmpty(idea(1 to 1.0, 2 to 2.0).head(-3))
    }

    @Test
    fun `head 03 on EMPTY is EMPTY singleton`() {
        assertEmpty(EMPTY.head(5))
    }

    @Test
    fun `tail 01 zero count is EMPTY singleton`() {
        assertEmpty(idea(1 to 1.0, 2 to 2.0, 3 to 3.0).tail(0))
    }

    @Test
    fun `tail 02 negative count is EMPTY singleton`() {
        assertEmpty(idea(1 to 1.0, 2 to 2.0).tail(-1))
    }

    @Test
    fun `tail 03 on EMPTY is EMPTY singleton`() {
        assertEmpty(EMPTY.tail(5))
    }

    // ---- n at or beyond size returns the same instance (no copy) ------------------------

    @Test
    fun `head 04 count equal to size returns the same instance`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        Assertions.assertSame(a, a.head(3))
    }

    @Test
    fun `head 05 count beyond size returns the same instance`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        Assertions.assertSame(a, a.head(99))
    }

    @Test
    fun `tail 04 count equal to size returns the same instance`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        Assertions.assertSame(a, a.tail(3))
    }

    @Test
    fun `tail 05 count beyond size returns the same instance`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        Assertions.assertSame(a, a.tail(99))
    }

    // ---- partial windows: content and endpoints ----------------------------------------

    @Test
    fun `head 06 takes the lowest levels`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0)
        val h = a.head(2)
        assertContent(listOf(1 to 10.0, 2 to 20.0), h)
        Assertions.assertEquals(1, h.firstLevel); Assertions.assertEquals(2, h.lastLevel); Assertions.assertEquals(
            2,
            h.size
        )
    }

    @Test
    fun `head 07 single element window`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0)
        val h = a.head(1)
        assertContent(listOf(1 to 10.0), h)
        Assertions.assertEquals(1, h.firstLevel); Assertions.assertEquals(1, h.lastLevel)
    }

    @Test
    fun `tail 06 takes the highest levels`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0)
        val t = a.tail(2)
        assertContent(listOf(3 to 30.0, 4 to 40.0), t)
        Assertions.assertEquals(3, t.firstLevel); Assertions.assertEquals(4, t.lastLevel); Assertions.assertEquals(
            2,
            t.size
        )
    }

    @Test
    fun `tail 07 single element window`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0)
        val t = a.tail(1)
        assertContent(listOf(3 to 30.0), t)
        Assertions.assertEquals(3, t.firstLevel); Assertions.assertEquals(3, t.lastLevel)
    }

    @Test
    fun `head 08 negative levels and quantities`() {
        val a = idea(-9 to -1.0, -4 to 2.0, -1 to -3.0, 5 to 4.0)
        assertContent(listOf(-9 to -1.0, -4 to 2.0), a.head(2))
    }

    @Test
    fun `tail 08 negative levels and quantities`() {
        val a = idea(-9 to -1.0, -4 to 2.0, -1 to -3.0, 5 to 4.0)
        assertContent(listOf(-1 to -3.0, 5 to 4.0), a.tail(2))
    }

    // ---- views share the backing array but stay independent of the source ----------------

    @Test
    fun `head 09 view does not alter the source array`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0)
        a.head(2)
        assertContent(listOf(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0), ws(a to 1.0))
        Assertions.assertEquals(4, a.size); Assertions.assertEquals(1, a.firstLevel); Assertions.assertEquals(
            4,
            a.lastLevel
        )
    }

    @Test
    fun `head 10 head and complementary tail partition the array`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0, 5 to 50.0)
        // disjoint level windows whose union reconstructs the original
        Assertions.assertSame(EMPTY, ws(a.head(2) to 1.0, a.tail(3) to 1.0, a to -1.0))
    }

    @Test
    fun `head 11 view can be further narrowed (head of head)`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0)
        assertContent(listOf(1 to 10.0), a.head(3).head(1))
    }

    @Test
    fun `tail 09 view can be further narrowed (tail of tail)`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0)
        assertContent(listOf(4 to 40.0), a.tail(3).tail(1))
    }

    @Test
    fun `head 12 tail then head selects an interior window`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0, 5 to 50.0)
        // last 3 -> {3,4,5}; first 2 of those -> {3,4}
        assertContent(listOf(3 to 30.0, 4 to 40.0), a.tail(3).head(2))
    }

    @Test
    fun `tail 10 head then tail selects an interior window`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0, 5 to 50.0)
        // first 4 -> {1,2,3,4}; last 2 of those -> {3,4}
        assertContent(listOf(3 to 30.0, 4 to 40.0), a.head(4).tail(2))
    }

    // ---- windows preserve zero entries (pure window, no zero-drop) -----------------------

    @Test
    fun `head 13 keeps zero entries inside the window`() {
        val a = idea(1 to 0.0, 2 to 5.0, 3 to 0.0, 4 to 7.0, allowZero = true)
        val h = a.head(2)
        Assertions.assertEquals(2, h.size); Assertions.assertEquals(1, h.firstLevel); Assertions.assertEquals(
            2,
            h.lastLevel
        )
    }

    @Test
    fun `tail 11 keeps zero entries inside the window`() {
        val a = idea(1 to 5.0, 2 to 0.0, 3 to 7.0, 4 to 0.0, allowZero = true)
        val t = a.tail(2)
        Assertions.assertEquals(2, t.size); Assertions.assertEquals(3, t.firstLevel); Assertions.assertEquals(
            4,
            t.lastLevel
        )
    }

    // ---- larger -------------------------------------------------------------------------

    @Test
    fun `head 14 large array window`() {
        val a = IntDoubleEntangledArray((0 until 1000).map { it to (it + 1).toDouble() }, allowZero = false)
        val h = a.head(250)
        assertContent((0 until 250).map { it to (it + 1).toDouble() }, h)
        Assertions.assertEquals(250, h.size); Assertions.assertEquals(0, h.firstLevel); Assertions.assertEquals(
            249,
            h.lastLevel
        )
    }

    @Test
    fun `tail 12 large array window`() {
        val a = IntDoubleEntangledArray((0 until 1000).map { it to (it + 1).toDouble() }, allowZero = false)
        val t = a.tail(250)
        assertContent((750 until 1000).map { it to (it + 1).toDouble() }, t)
        Assertions.assertEquals(250, t.size); Assertions.assertEquals(750, t.firstLevel); Assertions.assertEquals(
            999,
            t.lastLevel
        )
    }

    // ---- randomized cross-check ---------------------------------------------------------

    @Test
    fun `head tail 15 randomized windows match reference`() {
        val rnd = Random(41)
        repeat(800) {
            val spread = rnd.nextBoolean()
            // drop zeros up front: head/tail are pure windows that keep zeros, so the reference and
            // the window must agree on size, which assertContent enforces (zeros would skew it).
            val pairs = setPairs(rnd, spread).filter { it.second != 0.0 }
            if (pairs.isEmpty()) return@repeat
            // setPairs has unique ascending levels, so the backing order matches the sorted pairs.
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
        assertEmpty(idea(1 to 1.0, 2 to 2.0, 3 to 3.0).trim(0))
    }

    @Test
    fun `trim 02 negative count is EMPTY singleton`() {
        assertEmpty(idea(1 to 1.0, 2 to 2.0).trim(-3))
    }

    @Test
    fun `trim 03 on EMPTY is EMPTY singleton`() {
        assertEmpty(EMPTY.trim(5))
    }

    @Test
    fun `trim 04 count equal to size returns the same instance`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        Assertions.assertSame(a, a.trim(3))
    }

    @Test
    fun `trim 05 count beyond size returns the same instance`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        Assertions.assertSame(a, a.trim(99))
    }

    @Test
    fun `trim 06 odd surplus keeps the centre and drops the extra from the high end`() {
        // size 5, keep 1 -> drop 2 from the front, 2 from the back -> the middle element.
        assertContent(listOf(3 to 30.0), idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0, 5 to 50.0).trim(1))
        // size 5, keep 2 -> drop 1 from the front, 2 from the back.
        assertContent(
            listOf(2 to 20.0, 3 to 30.0),
            idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0, 5 to 50.0).trim(2),
        )
    }

    @Test
    fun `trim 07 even surplus drops evenly from both ends`() {
        // size 5, keep 3 -> drop 1 from each end.
        assertContent(
            listOf(2 to 20.0, 3 to 30.0, 4 to 40.0),
            idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0, 5 to 50.0).trim(3),
        )
        // size 4, keep 2 -> drop 1 from each end.
        assertContent(listOf(2 to 20.0, 3 to 30.0), idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0).trim(2))
    }

    @Test
    fun `trim 08 single element window`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0)
        val t = a.trim(1)
        Assertions.assertEquals(1, t.size)
        Assertions.assertEquals(2, t.firstLevel)
        Assertions.assertEquals(2, t.lastLevel)
    }

    @Test
    fun `trim 09 negative levels and quantities`() {
        val a = idea(-9 to -1.0, -4 to 2.0, -1 to -3.0, 5 to 4.0)
        assertContent(listOf(-4 to 2.0, -1 to -3.0), a.trim(2))
    }

    @Test
    fun `trim 10 view does not alter the source array`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0, 5 to 50.0)
        a.trim(3)
        assertContent(listOf(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0, 5 to 50.0), a)
    }

    @Test
    fun `trim 11 head trim tail partition the array`() {
        val a = idea(1 to 10.0, 2 to 20.0, 3 to 30.0, 4 to 40.0, 5 to 50.0, 6 to 60.0)
        val n = 2
        val dropFront = (a.size - n) / 2          // 2
        val highCount = a.size - n - dropFront    // 2
        Assertions.assertSame(
            EMPTY,
            ws(a.head(dropFront) to 1.0, a.trim(n) to 1.0, a.tail(highCount) to 1.0, a to -1.0),
        )
    }

    @Test
    fun `trim 12 keeps zero entries inside the window`() {
        val a = idea(1 to 5.0, 2 to 0.0, 3 to 7.0, 4 to 0.0, 5 to 9.0, allowZero = true)
        val t = a.trim(3)
        Assertions.assertEquals(3, t.size)
        Assertions.assertEquals(2, t.firstLevel)
        Assertions.assertEquals(4, t.lastLevel)
    }

    @Test
    fun `trim 13 large array window`() {
        val a = IntDoubleEntangledArray((0 until 1000).map { it to (it + 1).toDouble() }, allowZero = false)
        val t = a.trim(250)
        // size 1000, keep 250 -> drop 375 from each end.
        assertContent((375 until 625).map { it to (it + 1).toDouble() }, t)
        Assertions.assertEquals(250, t.size)
        Assertions.assertEquals(375, t.firstLevel)
        Assertions.assertEquals(624, t.lastLevel)
    }

    @Test
    fun `trim 14 randomized windows match reference`() {
        val rnd = Random(43)
        repeat(800) {
            val spread = rnd.nextBoolean()
            // trim is a pure window that keeps zeros, so drop zeros up front to keep the reference
            // and the window in agreement on size (assertContent enforces it).
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

    private fun roundTrip(a: IntDoubleEntangledArray) =
        IntDoubleEntangledArray.deserialize(a.serialize())

    @Test
    fun `serialize empty array is four zero bytes`() {
        val bytes = idea().serialize()
        Assertions.assertArrayEquals(byteArrayOf(0, 0, 0, 0), bytes)
    }

    @Test
    fun `deserialize empty array is the EMPTY singleton`() {
        assertEmpty(roundTrip(idea()))
        assertEmpty(roundTrip(EMPTY))
    }

    @Test
    fun `serialize single element has the expected byte layout`() {
        // 4 (count) + 1 (magic) + 1 (size, width 1) + 1 (startLevel, width 1) + 8 (double) = 15
        val bytes = idea(5 to 3.0).serialize()
        Assertions.assertEquals(15, bytes.size)

        val buffer = ByteBuffer.wrap(bytes)
        Assertions.assertEquals(1, buffer.getInt())          // entry count
        Assertions.assertEquals(0, buffer.get().toInt())     // magic: both widths are 1 -> codes 0
        Assertions.assertEquals(1, buffer.get().toInt())     // packet size
        Assertions.assertEquals(5, buffer.get().toInt())     // start level
        Assertions.assertEquals(3.0, buffer.getDouble())     // quantity
    }

    @Test
    fun `round-trips a single element`() {
        assertContent(listOf(5 to 3.0), roundTrip(idea(5 to 3.0)))
    }

    @Test
    fun `round-trips a single contiguous packet`() {
        val pairs = listOf(10 to 1.0, 11 to 2.0, 12 to 3.0, 13 to 4.0)
        assertContent(pairs, roundTrip(idea(*pairs.toTypedArray())))
    }

    @Test
    fun `round-trips multiple packets separated by gaps`() {
        val pairs = listOf(1 to 1.0, 2 to 2.0, 100 to 5.0, 101 to 6.0, 5000 to 9.0)
        assertContent(pairs, roundTrip(idea(*pairs.toTypedArray())))
    }

    @Test
    fun `round-trips negative levels and negative quantities`() {
        val pairs = listOf(-5 to -2.0, -4 to 3.0, 0 to 1.0, 7 to -8.0)
        assertContent(pairs, roundTrip(idea(*pairs.toTypedArray())))
    }

    @Test
    fun `round-trips levels that force wider integer encodings`() {
        // start levels beyond Short range force a 4-byte start-level width.
        val pairs = listOf(70_000 to 1.0, 200_000 to 2.0, 200_001 to 3.0)
        assertContent(pairs, roundTrip(idea(*pairs.toTypedArray())))
    }

    @Test
    fun `round-trips a packet long enough to force a wider size encoding`() {
        // 300 consecutive levels -> packet size needs 2 bytes.
        val pairs = (0 until 300).map { (1000 + it) to (it + 1).toDouble() }
        assertContent(pairs, roundTrip(build(pairs)))
    }

    @Test
    fun `deserialize rejects a truncated header`() {
        assertThrows<IllegalArgumentException> { IntDoubleEntangledArray.deserialize(byteArrayOf()) }
        assertThrows<IllegalArgumentException> { IntDoubleEntangledArray.deserialize(byteArrayOf(0, 0, 0)) }
    }

    @Test
    fun `deserialize rejects a truncated body`() {
        val bytes = idea(1 to 1.0, 2 to 2.0).serialize()
        // Keep the count header intact but cut into the packet payload.
        val truncated = bytes.copyOf(bytes.size - 3)
        assertThrows<IllegalArgumentException> { IntDoubleEntangledArray.deserialize(truncated) }
    }

    @Test
    fun `serialize then deserialize round-trips random arrays`() {
        val rnd = Random(20260615)
        repeat(500) {
            val n = rnd.nextInt(1, 50)
            var level = rnd.nextInt(-1000, 1000)
            val pairs = ArrayList<Pair<Int, Double>>(n)
            repeat(n) {
                level += rnd.nextInt(1, 6) // strictly ascending, with occasional gaps
                val q = rnd.nextInt(-50, 50).toDouble()
                if (q != 0.0) pairs.add(level to q)
            }
            if (pairs.isEmpty()) return@repeat
            assertContent(pairs, roundTrip(build(pairs)))
        }
    }

    // =====================================================================================
    // zip (static logarithmic-bucket aggregation)
    // =====================================================================================

    /** The bucket an individual level falls into, matching the production formula. */
    private fun bucketOf(level: Double, invLnExp: Double): Int = (ln(level) * invLnExp).toInt()

    private fun zip(levels: DoubleArray, quantities: DoubleArray, from: Int, to: Int, invLnExp: Double) =
        IntDoubleEntangledArray.zip(levels, quantities, from, to, invLnExp)

    /** Reference: bucket each entry in `[from, to)`, sum within buckets, drop zero sums, ascending order. */
    private fun zipReference(levels: DoubleArray, quantities: DoubleArray, from: Int, to: Int, invLnExp: Double): List<Pair<Int, Double>> {
        val acc = sortedMapOf<Int, Double>()
        for (i in from until to) acc.merge(bucketOf(levels[i], invLnExp), quantities[i], Double::plus)
        return acc.entries.filter { it.value != 0.0 }.map { it.key to it.value }
    }

    @Test
    fun `zip of an empty window is EMPTY`() {
        val levels = doubleArrayOf(100.0, 101.0, 102.0)
        val quantities = doubleArrayOf(1.0, 2.0, 3.0)
        assertEmpty(zip(levels, quantities, 0, 0, 1.0))
        assertEmpty(zip(levels, quantities, 2, 2, 1.0))
        assertEmpty(zip(DoubleArray(0), DoubleArray(0), 0, 0, 1.0))
    }

    @Test
    fun `zip of a single entry yields one bucket`() {
        val invLnExp = 1.0 / ln(1.001)
        assertContent(listOf(bucketOf(100.0, invLnExp) to 7.0), zip(doubleArrayOf(100.0), doubleArrayOf(7.0), 0, 1, invLnExp))
    }

    @Test
    fun `zip sums contiguous entries sharing a bucket`() {
        // ratio ~ e (invLnExp = 1): 100, 100.5, 101 share a bucket; 200 falls in the next.
        val levels = doubleArrayOf(100.0, 100.5, 101.0, 200.0)
        val quantities = doubleArrayOf(1.0, 2.0, 3.0, 9.0)
        assertContent(listOf(bucketOf(100.0, 1.0) to 6.0, bucketOf(200.0, 1.0) to 9.0), zip(levels, quantities, 0, 4, 1.0))
    }

    @Test
    fun `zip drops a bucket whose values cancel to zero`() {
        val levels = doubleArrayOf(100.0, 100.5, 200.0)
        val quantities = doubleArrayOf(5.0, -5.0, 3.0)
        assertContent(listOf(bucketOf(200.0, 1.0) to 3.0), zip(levels, quantities, 0, 3, 1.0))
    }

    @Test
    fun `zip keeps a negative bucket total`() {
        assertContent(listOf(bucketOf(100.0, 1.0) to -3.0), zip(doubleArrayOf(100.0, 100.5), doubleArrayOf(2.0, -5.0), 0, 2, 1.0))
    }

    @Test
    fun `zip of everything cancelling away is EMPTY`() {
        assertEmpty(zip(doubleArrayOf(100.0, 100.5), doubleArrayOf(5.0, -5.0), 0, 2, 1.0))
    }

    @Test
    fun `zip only aggregates the requested window`() {
        val levels = DoubleArray(20) { 100.0 + it }
        val quantities = DoubleArray(20) { 1.0 }
        val invLnExp = 1.0 / ln(1.005)
        assertContent(zipReference(levels, quantities, 5, 15, invLnExp), zip(levels, quantities, 5, 15, invLnExp))
    }

    @Test
    fun `zip rejects mismatched array sizes`() {
        assertThrows<IllegalArgumentException> { zip(doubleArrayOf(1.0, 2.0), doubleArrayOf(1.0), 0, 1, 1.0) }
    }

    @Test
    fun `zip rejects an out-of-bounds window`() {
        val levels = doubleArrayOf(100.0, 101.0)
        val quantities = doubleArrayOf(1.0, 2.0)
        assertThrows<IndexOutOfBoundsException> { zip(levels, quantities, 0, 3, 1.0) }
        assertThrows<IndexOutOfBoundsException> { zip(levels, quantities, -1, 2, 1.0) }
    }

    @Test
    fun `zip matches the reference including negatives and cancellations`() {
        val rnd = Random(7)
        repeat(500) {
            val n = rnd.nextInt(1, 40)
            val levels = DoubleArray(n)
            val quantities = DoubleArray(n)
            var price = 10.0 + rnd.nextDouble() * 90.0
            for (i in 0 until n) {
                price += rnd.nextDouble() * 1.5 + 1e-3 // strictly ascending positive prices
                levels[i] = price
                quantities[i] = if (rnd.nextDouble() < 0.2) 0.0 else rnd.nextInt(-30, 31).toDouble()
            }
            val invLnExp = 1.0 / ln(1.0 + rnd.nextDouble() * 0.1)
            assertContent(zipReference(levels, quantities, 0, n, invLnExp), zip(levels, quantities, 0, n, invLnExp))
        }
    }

    // =====================================================================================
    // dropAfter / dropStrictAfter / dropBefore / dropStrictBefore
    // =====================================================================================

    /** Levels 1,3,5,7,9 (ascending, with gaps so `num` can fall between entries). */
    private fun dropSample() = idea(1 to 10.0, 3 to 20.0, 5 to 30.0, 7 to 40.0, 9 to 50.0)

    @Test
    fun `dropAfter keeps levels at or below num`() {
        assertContent(listOf(1 to 10.0, 3 to 20.0, 5 to 30.0), dropSample().dropAfter(5))
        assertContent(listOf(1 to 10.0, 3 to 20.0, 5 to 30.0), dropSample().dropAfter(6)) // num absent
    }

    @Test
    fun `dropAfter returns this when nothing is above num`() {
        val a = dropSample()
        Assertions.assertSame(a, a.dropAfter(9))
        Assertions.assertSame(a, a.dropAfter(100))
    }

    @Test
    fun `dropAfter is EMPTY when everything is above num`() {
        assertEmpty(dropSample().dropAfter(0))
    }

    @Test
    fun `dropStrictAfter keeps levels strictly below num`() {
        assertContent(listOf(1 to 10.0, 3 to 20.0), dropSample().dropStrictAfter(5))
        assertContent(listOf(1 to 10.0, 3 to 20.0, 5 to 30.0), dropSample().dropStrictAfter(6)) // num absent
    }

    @Test
    fun `dropStrictAfter returns this when num is above every level`() {
        val a = dropSample()
        Assertions.assertSame(a, a.dropStrictAfter(10))
    }

    @Test
    fun `dropStrictAfter is EMPTY when num is at or below the first level`() {
        assertEmpty(dropSample().dropStrictAfter(1))
        assertEmpty(dropSample().dropStrictAfter(0))
    }

    @Test
    fun `dropBefore keeps levels at or above num`() {
        assertContent(listOf(5 to 30.0, 7 to 40.0, 9 to 50.0), dropSample().dropBefore(5))
        assertContent(listOf(7 to 40.0, 9 to 50.0), dropSample().dropBefore(6)) // num absent
    }

    @Test
    fun `dropBefore returns this when nothing is below num`() {
        val a = dropSample()
        Assertions.assertSame(a, a.dropBefore(1))
        Assertions.assertSame(a, a.dropBefore(0))
    }

    @Test
    fun `dropBefore is EMPTY when everything is below num`() {
        assertEmpty(dropSample().dropBefore(10))
    }

    @Test
    fun `dropStrictBefore keeps levels strictly above num`() {
        assertContent(listOf(7 to 40.0, 9 to 50.0), dropSample().dropStrictBefore(5))
        assertContent(listOf(7 to 40.0, 9 to 50.0), dropSample().dropStrictBefore(6)) // num absent
    }

    @Test
    fun `dropStrictBefore returns this when num is below every level`() {
        val a = dropSample()
        Assertions.assertSame(a, a.dropStrictBefore(0))
    }

    @Test
    fun `dropStrictBefore is EMPTY when num is at or above the last level`() {
        assertEmpty(dropSample().dropStrictBefore(9))
        assertEmpty(dropSample().dropStrictBefore(100))
    }

    @Test
    fun `drops on an empty array are the EMPTY singleton`() {
        assertEmpty(EMPTY.dropAfter(5))
        assertEmpty(EMPTY.dropStrictAfter(5))
        assertEmpty(EMPTY.dropBefore(5))
        assertEmpty(EMPTY.dropStrictBefore(5))
    }

    @Test
    fun `drops compose to carve out a middle range`() {
        assertContent(listOf(3 to 20.0, 5 to 30.0, 7 to 40.0), dropSample().dropBefore(3).dropAfter(7))
        assertContent(listOf(5 to 30.0), dropSample().dropStrictBefore(3).dropStrictAfter(7))
    }

    @Test
    fun `drops match a filter reference for random arrays`() {
        val rnd = Random(31)
        repeat(500) {
            val n = rnd.nextInt(1, 40)
            var level = rnd.nextInt(-1000, 1000)
            val pairs = ArrayList<Pair<Int, Double>>(n)
            repeat(n) {
                level += rnd.nextInt(1, 6) // strictly ascending unique levels
                pairs.add(level to rnd.nextInt(1, 50).toDouble())
            }
            val a = idea(*pairs.toTypedArray())
            val num = rnd.nextInt(-1100, 1100)
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
        val a = idea(1 to 5.0, 3 to 7.0, 5 to 9.0)
        Assertions.assertEquals(1, a.firstNonZeroLevel())
        Assertions.assertEquals(5, a.lastNonZeroLevel())
    }

    @Test
    fun `firstNonZeroLevel skips leading zeros`() {
        val a = idea(1 to 0.0, 2 to 0.0, 3 to 5.0, 4 to 7.0, allowZero = true)
        Assertions.assertEquals(3, a.firstNonZeroLevel())
    }

    @Test
    fun `lastNonZeroLevel skips trailing zeros`() {
        val a = idea(1 to 5.0, 2 to 7.0, 3 to 0.0, 4 to 0.0, allowZero = true)
        Assertions.assertEquals(2, a.lastNonZeroLevel())
    }

    @Test
    fun `non-zero levels skip zeros on both ends`() {
        val a = idea(1 to 0.0, 2 to 5.0, 3 to 0.0, 4 to 7.0, 5 to 0.0, allowZero = true)
        Assertions.assertEquals(2, a.firstNonZeroLevel())
        Assertions.assertEquals(4, a.lastNonZeroLevel())
    }

    @Test
    fun `non-zero levels on an all-zero array are null`() {
        val a = idea(1 to 0.0, 2 to 0.0, allowZero = true)
        Assertions.assertNull(a.firstNonZeroLevel())
        Assertions.assertNull(a.lastNonZeroLevel())
    }

    @Test
    fun `non-zero levels on an empty array are null`() {
        Assertions.assertNull(EMPTY.firstNonZeroLevel())
        Assertions.assertNull(idea().lastNonZeroLevel())
    }

    @Test
    fun `non-zero levels respect the window`() {
        val a = idea(1 to 5.0, 2 to 0.0, 3 to 7.0, 4 to 0.0, allowZero = true)
        val w = a.tail(2) // window over (3, 7.0) and (4, 0.0)
        Assertions.assertEquals(3, w.firstNonZeroLevel())
        Assertions.assertEquals(3, w.lastNonZeroLevel())
    }

    // =====================================================================================
    // toLevelValueList
    // =====================================================================================

    @Test
    fun `toLevelValueList of an empty array is empty`() {
        Assertions.assertEquals(emptyList<List<Number>>(), EMPTY.toLevelValueList())
        Assertions.assertEquals(emptyList<List<Number>>(), idea().toLevelValueList())
    }

    @Test
    fun `toLevelValueList returns level-value rows in ascending order`() {
        val a = idea(5 to 7.0, 1 to 3.0, 3 to 9.0)
        Assertions.assertEquals(
            listOf(listOf<Number>(1, 3.0), listOf<Number>(3, 9.0), listOf<Number>(5, 7.0)),
            a.toLevelValueList(),
        )
    }

    @Test
    fun `toLevelValueList descending returns rows from highest level to lowest`() {
        val a = idea(5 to 7.0, 1 to 3.0, 3 to 9.0)
        Assertions.assertEquals(
            listOf(listOf<Number>(5, 7.0), listOf<Number>(3, 9.0), listOf<Number>(1, 3.0)),
            a.toLevelValueList(descending = true),
        )
    }

    @Test
    fun `toLevelValueList includes zero entries`() {
        val a = idea(1 to 0.0, 2 to 5.0, allowZero = true)
        Assertions.assertEquals(
            listOf(listOf<Number>(1, 0.0), listOf<Number>(2, 5.0)),
            a.toLevelValueList(),
        )
    }

    @Test
    fun `toLevelValueList respects the window`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 4.0)
        Assertions.assertEquals(
            listOf(listOf<Number>(2, 2.0), listOf<Number>(3, 3.0)),
            a.tail(3).head(2).toLevelValueList(),
        )
    }

    // =====================================================================================
    // sum  (unit-multiplier weightedSum)
    // =====================================================================================

    private fun sm(vararg arrays: IntDoubleEntangledArray) =
        IntDoubleEntangledArray.sum(arrays.toList())

    /** Asserts [actual] holds the same canonical content as [expected] (both already canonical). */
    private fun assertSameContent(expected: IntDoubleEntangledArray, actual: IntDoubleEntangledArray) {
        Assertions.assertEquals(expected.size, actual.size, "size")
        Assertions.assertSame(EMPTY, ws(actual to 1.0, expected to -1.0), "content differs from expected")
    }

    @Test
    fun `sum empty list is EMPTY`() { assertEmpty(IntDoubleEntangledArray.sum(emptyList())) }

    @Test
    fun `sum single empty array is EMPTY`() { assertEmpty(sm(EMPTY)) }

    @Test
    fun `sum all empty arrays is EMPTY`() { assertEmpty(sm(EMPTY, idea(), EMPTY)) }

    @Test
    fun `sum single canonical array returns the same instance`() {
        val a = idea(1 to 2.0, 2 to 3.0, 3 to 4.0)
        Assertions.assertSame(a, sm(a))
    }

    @Test
    fun `sum single array with zeros drops them`() {
        val a = idea(1 to 2.0, 2 to 0.0, 3 to 4.0, 4 to 0.0, allowZero = true)
        assertContent(listOf(1 to 2.0, 3 to 4.0), sm(a))
    }

    @Test
    fun `sum single all-zero array is EMPTY`() {
        assertEmpty(sm(idea(1 to 0.0, 2 to 0.0, allowZero = true)))
    }

    @Test
    fun `sum two disjoint adjacent dense path`() {
        assertContent(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 4.0),
            sm(idea(1 to 1.0, 2 to 2.0), idea(3 to 3.0, 4 to 4.0)))
    }

    @Test
    fun `sum two overlapping sums shared levels`() {
        assertContent(listOf(1 to 1.0, 2 to 5.0, 3 to 3.0),
            sm(idea(1 to 1.0, 2 to 2.0), idea(2 to 3.0, 3 to 3.0)))
    }

    @Test
    fun `sum two full cancellation is EMPTY`() {
        assertEmpty(sm(idea(1 to 2.0, 2 to 3.0), idea(1 to -2.0, 2 to -3.0)))
    }

    @Test
    fun `sum two partial cancellation drops cancelled level`() {
        assertContent(listOf(2 to 3.0), sm(idea(1 to 2.0, 2 to 3.0), idea(1 to -2.0)))
    }

    @Test
    fun `sum two spread disjoint merge path`() {
        assertContent(listOf(1 to 1.0, 1000 to 2.0), sm(idea(1 to 1.0), idea(1000 to 2.0)))
    }

    @Test
    fun `sum three overlapping`() {
        assertContent(listOf(1 to 3.0, 2 to 2.0, 3 to 1.0),
            sm(idea(1 to 1.0, 2 to 1.0), idea(1 to 1.0, 3 to 1.0), idea(1 to 1.0, 2 to 1.0)))
    }

    @Test
    fun `sum N spread sparse merge path`() {
        assertContent(listOf(0 to 1.0, 500 to 2.0, 1000 to 3.0),
            sm(idea(0 to 1.0), idea(500 to 2.0), idea(1000 to 3.0)))
    }

    @Test
    fun `sum ignores empty terms and keeps order`() {
        assertContent(listOf(1 to 1.0, 5 to 2.0), sm(EMPTY, idea(1 to 1.0), EMPTY, idea(5 to 2.0)))
    }

    @Test
    fun `sum equivalent to weightedSum with unit multipliers - randomized`() {
        val rnd = Random(0xBEEF)
        repeat(3000) {
            val count = rnd.nextInt(1, 6)
            val maxLevel = if (rnd.nextBoolean()) 8 else 400 // exercise both dense and merge paths
            val arrays = List(count) {
                val len = rnd.nextInt(0, 6)
                val pairs = (0 until len).map { rnd.nextInt(0, maxLevel) to rnd.nextInt(-4, 5).toDouble() }
                IntDoubleEntangledArray(pairs, allowZero = true)
            }
            val viaWs = IntDoubleEntangledArray.weightedSum(arrays.map { it to 1.0 })
            val viaSum = IntDoubleEntangledArray.sum(arrays)
            assertSameContent(viaWs, viaSum)
        }
    }
}