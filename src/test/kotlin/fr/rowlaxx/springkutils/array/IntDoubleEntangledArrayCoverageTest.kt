package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.lang.reflect.Method

/**
 * Gap-filling coverage for [IntDoubleEntangledArray] that the black-box [IntDoubleEntangledArrayTest]
 * does not reach:
 *
 *  1. The private companion / instance helpers, exercised directly through reflection: the sort
 *     entry points ([sort]/[classicSort]), the canonicalising [fuse], the binary-search bounds
 *     ([lowerBound]/[upperBound]), the window helper ([windowLevels]), the scratch trimmer
 *     ([result]), the dense compactors ([collectDenseSet]/[collectDenseSum]), and the arity-specialised
 *     set / sum kernels (`*2` and `*N` variants of dense and merge). The prompt asked for the generic
 *     family names (denseSet, mergeSum, ...); in this source they are split into arity-specific methods
 *     (denseSet2/denseSetN, mergeSum2/mergeSumN, denseSumOf2/denseSumOfN, ...) which are the real
 *     implementations and are what is tested here.
 *  2. Allocation / no-gross-regression micro-benchmarks (no boxed keys, fast paths allocate nothing).
 *  3. Double-specific public edge states (NaN / Infinity / -0.0 / tiny / huge quantities).
 *
 * Private methods are invoked reflectively. Quantities are compared with a delta. The dense/merge
 * kernels write their survivors back into the scratch buffers handed in and return a freshly trimmed
 * array via `result`, so every call here passes fresh scratch arrays sized to the worst case.
 */
class IntDoubleEntangledArrayCoverageTest {

    private val EMPTY = IntDoubleEntangledArray.EMPTY
    private val DELTA = 1e-9

    private fun idea(vararg pairs: Pair<Int, Double>, allowZero: Boolean = false) =
        IntDoubleEntangledArray(pairs.toList(), allowZero)

    private fun ws(vararg terms: Pair<IntDoubleEntangledArray, Double>) =
        IntDoubleEntangledArray.weightedSum(terms.toList())

    // ---- reflection plumbing ------------------------------------------------------------

    private val cls = IntDoubleEntangledArray::class.java

    private fun m(name: String, vararg params: Class<*>): Method =
        cls.getDeclaredMethod(name, *params).apply { isAccessible = true }

    /** Companion-object instance (the receiver for every private static-like helper). */
    private val companion: Any = run {
        val f = cls.getDeclaredField("Companion")
        f.isAccessible = true
        f.get(null)
    }

    private val companionCls = companion.javaClass

    private fun cm(name: String, vararg params: Class<*>): Method =
        companionCls.getDeclaredMethod(name, *params).apply { isAccessible = true }

    private fun invokeC(method: Method, vararg args: Any?): Any? = method.invoke(companion, *args)

    /** Reads the [start, end) content of an array as ascending (level, quantity) pairs. */
    private fun content(a: IntDoubleEntangledArray): List<Pair<Int, Double>> =
        a.toLevelValueList().map { it[0].toInt() to it[1].toDouble() }

    private fun assertContentEq(expected: List<Pair<Int, Double>>, actual: IntDoubleEntangledArray) {
        val got = content(actual)
        Assertions.assertEquals(expected.size, got.size, "size; got=$got")
        for (i in expected.indices) {
            Assertions.assertEquals(expected[i].first, got[i].first, "level at $i")
            Assertions.assertEquals(expected[i].second, got[i].second, DELTA, "quantity at $i")
        }
    }

    // =====================================================================================
    // classicSort  (insertion sort, in place over [0, end))
    // =====================================================================================

    private fun classicSort(levels: IntArray, quantities: DoubleArray, end: Int) =
        invokeC(cm("classicSort", IntArray::class.java, DoubleArray::class.java, Int::class.java), levels, quantities, end)

    private fun sortNative(levels: IntArray, quantities: DoubleArray, end: Int) =
        invokeC(cm("sort", IntArray::class.java, DoubleArray::class.java, Int::class.java), levels, quantities, end)

    @Test
    fun `classicSort empty and single are no-ops`() {
        classicSort(IntArray(0), DoubleArray(0), 0)
        val l = intArrayOf(5); val q = doubleArrayOf(7.0)
        classicSort(l, q, 1)
        Assertions.assertArrayEquals(intArrayOf(5), l)
        Assertions.assertEquals(7.0, q[0], DELTA)
    }

    @Test
    fun `classicSort reverse sorted`() {
        val l = intArrayOf(5, 4, 3, 2, 1); val q = doubleArrayOf(50.0, 40.0, 30.0, 20.0, 10.0)
        classicSort(l, q, 5)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3, 4, 5), l)
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0), q, DELTA)
    }

    @Test
    fun `classicSort already sorted unchanged`() {
        val l = intArrayOf(1, 2, 3); val q = doubleArrayOf(1.0, 2.0, 3.0)
        classicSort(l, q, 3)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3), l)
    }

    @Test
    fun `classicSort keeps quantities paired with their levels`() {
        val l = intArrayOf(3, 1, 2); val q = doubleArrayOf(0.3, 0.1, 0.2)
        classicSort(l, q, 3)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3), l)
        Assertions.assertArrayEquals(doubleArrayOf(0.1, 0.2, 0.3), q, DELTA)
    }

    @Test
    fun `classicSort stable for equal levels (duplicates retained)`() {
        val l = intArrayOf(2, 1, 2, 1); val q = doubleArrayOf(20.0, 10.0, 21.0, 11.0)
        classicSort(l, q, 4)
        Assertions.assertArrayEquals(intArrayOf(1, 1, 2, 2), l)
        // insertion sort is stable: original relative order of equal keys preserved
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 11.0, 20.0, 21.0), q, DELTA)
    }

    @Test
    fun `classicSort negative levels`() {
        val l = intArrayOf(2, -5, 0, -1); val q = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        classicSort(l, q, 4)
        Assertions.assertArrayEquals(intArrayOf(-5, -1, 0, 2), l)
        Assertions.assertArrayEquals(doubleArrayOf(2.0, 4.0, 3.0, 1.0), q, DELTA)
    }

    @Test
    fun `classicSort sorts only the prefix end`() {
        val l = intArrayOf(3, 1, 2, 99, 88); val q = doubleArrayOf(3.0, 1.0, 2.0, 9.9, 8.8)
        classicSort(l, q, 3) // leave the tail untouched
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3, 99, 88), l)
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 9.9, 8.8), q, DELTA)
    }

    // =====================================================================================
    // sort  (dispatch: ASC short-circuit, DESC reverse, insertion <= threshold, key-sort above)
    // =====================================================================================

    @Test
    fun `sort end le 1 returns immediately`() {
        sortNative(IntArray(0), DoubleArray(0), 0)
        val l = intArrayOf(9); val q = doubleArrayOf(1.0)
        sortNative(l, q, 1)
        Assertions.assertEquals(9, l[0])
    }

    @Test
    fun `sort ascending input short-circuits unchanged`() {
        val l = intArrayOf(1, 2, 3, 4); val q = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        sortNative(l, q, 4)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3, 4), l)
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), q, DELTA)
    }

    @Test
    fun `sort strictly descending input is reversed`() {
        val l = intArrayOf(4, 3, 2, 1); val q = doubleArrayOf(40.0, 30.0, 20.0, 10.0)
        sortNative(l, q, 4)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3, 4), l)
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0, 40.0), q, DELTA)
    }

    @Test
    fun `sort unsorted small input below threshold uses insertion`() {
        val l = intArrayOf(3, 1, 4, 1, 5, 9, 2, 6); val q = DoubleArray(8) { it.toDouble() }
        sortNative(l, q, 8)
        Assertions.assertArrayEquals(intArrayOf(1, 1, 2, 3, 4, 5, 6, 9), l)
    }

    @Test
    fun `sort large unsorted input above threshold uses key-sort path`() {
        // > INSERTION_THRESHOLD (40) elements, unsorted -> the packed long key sort.
        val n = 200
        val levels = IntArray(n) { (n - it) * 3 % 137 } // pseudo-random-ish, not monotone
        // make it definitely NONE-direction by injecting an inversion early
        levels[0] = 5; levels[1] = 1
        val q = DoubleArray(n) { it.toDouble() }
        // capture expected via a paired stable sort
        val expected = (0 until n).sortedBy { levels[it] }.map { levels[it] to it.toDouble() }
        sortNative(levels, q, n)
        val expLevels = expected.map { it.first }.toIntArray()
        Assertions.assertArrayEquals(expLevels, levels)
        // every (level -> original index quantity) preserved
        for (i in 0 until n) Assertions.assertEquals(expected[i].second, q[i], DELTA)
    }

    @Test
    fun `sort key-sort keeps level-quantity pairing for many duplicates`() {
        val n = 100
        val levels = IntArray(n) { (it * 7) % 5 } // only 5 distinct levels, unsorted
        levels[0] = 3; levels[1] = 0 // ensure NONE direction
        val q = DoubleArray(n) { it * 0.5 }
        val expected = (0 until n).sortedBy { levels[it] }.map { levels[it] to it * 0.5 }
        sortNative(levels, q, n)
        for (i in 0 until n) {
            Assertions.assertEquals(expected[i].first, levels[i])
            Assertions.assertEquals(expected[i].second, q[i], DELTA)
        }
    }

    // =====================================================================================
    // fuse  (returns the count removed; canonicalises duplicates / zeros in place)
    // =====================================================================================

    private fun fuse(levels: IntArray, quantities: DoubleArray, end: Int, allowZero: Boolean): Int =
        invokeC(cm("fuse", IntArray::class.java, DoubleArray::class.java, Int::class.java, Boolean::class.java),
            levels, quantities, end, allowZero) as Int

    @Test
    fun `fuse no duplicates no zeros removes nothing`() {
        val l = intArrayOf(1, 2, 3); val q = doubleArrayOf(1.0, 2.0, 3.0)
        Assertions.assertEquals(0, fuse(l, q, 3, false))
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3), l.copyOf(3))
    }

    @Test
    fun `fuse merges adjacent duplicate levels by summing`() {
        val l = intArrayOf(1, 1, 2); val q = doubleArrayOf(2.0, 3.0, 5.0)
        val removed = fuse(l, q, 3, true)
        Assertions.assertEquals(1, removed)
        Assertions.assertArrayEquals(intArrayOf(1, 2), l.copyOf(2))
        Assertions.assertArrayEquals(doubleArrayOf(5.0, 5.0), q.copyOf(2), DELTA)
    }

    @Test
    fun `fuse run of three duplicates`() {
        val l = intArrayOf(4, 4, 4); val q = doubleArrayOf(1.0, 2.0, 3.0)
        Assertions.assertEquals(2, fuse(l, q, 3, true))
        Assertions.assertArrayEquals(intArrayOf(4), l.copyOf(1))
        Assertions.assertEquals(6.0, q[0], DELTA)
    }

    @Test
    fun `fuse duplicates cancelling to zero dropped when allowZero false`() {
        val l = intArrayOf(1, 1, 2); val q = doubleArrayOf(2.0, -2.0, 5.0)
        val removed = fuse(l, q, 3, false)
        Assertions.assertEquals(2, removed)
        Assertions.assertArrayEquals(intArrayOf(2), l.copyOf(1))
        Assertions.assertEquals(5.0, q[0], DELTA)
    }

    @Test
    fun `fuse duplicates cancelling to zero kept when allowZero true`() {
        val l = intArrayOf(1, 1, 2); val q = doubleArrayOf(2.0, -2.0, 5.0)
        val removed = fuse(l, q, 3, true)
        Assertions.assertEquals(1, removed)
        Assertions.assertArrayEquals(intArrayOf(1, 2), l.copyOf(2))
        Assertions.assertEquals(0.0, q[0], DELTA)
        Assertions.assertEquals(5.0, q[1], DELTA)
    }

    @Test
    fun `fuse leading clean prefix is skipped before first duplicate`() {
        // prefix 1,2 already canonical; the duplicate run starts at 3.
        val l = intArrayOf(1, 2, 3, 3); val q = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        Assertions.assertEquals(1, fuse(l, q, 4, true))
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3), l.copyOf(3))
        Assertions.assertEquals(7.0, q[2], DELTA)
    }

    @Test
    fun `fuse drops standalone zero when allowZero false`() {
        val l = intArrayOf(1, 2, 3); val q = doubleArrayOf(1.0, 0.0, 3.0)
        Assertions.assertEquals(1, fuse(l, q, 3, false))
        Assertions.assertArrayEquals(intArrayOf(1, 3), l.copyOf(2))
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 3.0), q.copyOf(2), DELTA)
    }

    @Test
    fun `fuse keeps standalone zero when allowZero true`() {
        val l = intArrayOf(1, 2, 3); val q = doubleArrayOf(1.0, 0.0, 3.0)
        Assertions.assertEquals(0, fuse(l, q, 3, true))
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3), l.copyOf(3))
    }

    @Test
    fun `fuse all zeros allowZero false removes everything`() {
        val l = intArrayOf(1, 2, 3); val q = doubleArrayOf(0.0, 0.0, 0.0)
        Assertions.assertEquals(3, fuse(l, q, 3, false))
    }

    @Test
    fun `fuse floating point summation accumulates`() {
        val l = intArrayOf(7, 7, 7, 7); val q = doubleArrayOf(0.1, 0.2, 0.3, 0.4)
        Assertions.assertEquals(3, fuse(l, q, 4, true))
        Assertions.assertEquals(1.0, q[0], 1e-9)
    }

    @Test
    fun `fuse empty range removes nothing`() {
        Assertions.assertEquals(0, fuse(IntArray(0), DoubleArray(0), 0, false))
    }

    @Test
    fun `fuse single zero allowZero false removed`() {
        val l = intArrayOf(5); val q = doubleArrayOf(0.0)
        Assertions.assertEquals(1, fuse(l, q, 1, false))
    }

    @Test
    fun `fuse mixed duplicates and zeros allowZero false`() {
        // 1:{2,3}=5, 2:0 drop, 3:{1,-1}=0 drop, 4:7
        val l = intArrayOf(1, 1, 2, 3, 3, 4); val q = doubleArrayOf(2.0, 3.0, 0.0, 1.0, -1.0, 7.0)
        val removed = fuse(l, q, 6, false)
        Assertions.assertArrayEquals(intArrayOf(1, 4), l.copyOf(6 - removed))
        Assertions.assertArrayEquals(doubleArrayOf(5.0, 7.0), q.copyOf(6 - removed), DELTA)
    }

    // =====================================================================================
    // lowerBound / upperBound  (instance, private, on the [start, end) window)
    // =====================================================================================

    private fun lowerBound(a: IntDoubleEntangledArray, num: Int): Int =
        m("lowerBound", Int::class.java).invoke(a, num) as Int

    private fun upperBound(a: IntDoubleEntangledArray, num: Int): Int =
        m("upperBound", Int::class.java).invoke(a, num) as Int

    @Test
    fun `lowerBound on empty array returns end (0)`() {
        Assertions.assertEquals(0, lowerBound(EMPTY, 5))
    }

    @Test
    fun `upperBound on empty array returns end (0)`() {
        Assertions.assertEquals(0, upperBound(EMPTY, 5))
    }

    @Test
    fun `lowerBound present level returns its index`() {
        val a = idea(1 to 1.0, 3 to 1.0, 5 to 1.0, 7 to 1.0)
        Assertions.assertEquals(0, lowerBound(a, 1))
        Assertions.assertEquals(1, lowerBound(a, 3))
        Assertions.assertEquals(3, lowerBound(a, 7))
    }

    @Test
    fun `lowerBound missing level returns insertion point`() {
        val a = idea(1 to 1.0, 3 to 1.0, 5 to 1.0)
        Assertions.assertEquals(1, lowerBound(a, 2)) // first >= 2 is index of 3
        Assertions.assertEquals(0, lowerBound(a, 0)) // below all
        Assertions.assertEquals(3, lowerBound(a, 6)) // above all -> end
    }

    @Test
    fun `upperBound present level returns index after it`() {
        val a = idea(1 to 1.0, 3 to 1.0, 5 to 1.0)
        Assertions.assertEquals(1, upperBound(a, 1)) // first > 1
        Assertions.assertEquals(2, upperBound(a, 3))
        Assertions.assertEquals(3, upperBound(a, 5)) // -> end
    }

    @Test
    fun `upperBound missing level returns insertion point`() {
        val a = idea(1 to 1.0, 3 to 1.0, 5 to 1.0)
        Assertions.assertEquals(1, upperBound(a, 2))
        Assertions.assertEquals(0, upperBound(a, 0))
        Assertions.assertEquals(3, upperBound(a, 100))
    }

    @Test
    fun `lowerBound and upperBound single element`() {
        val a = idea(5 to 1.0)
        Assertions.assertEquals(0, lowerBound(a, 5))
        Assertions.assertEquals(1, upperBound(a, 5))
        Assertions.assertEquals(0, lowerBound(a, 4))
        Assertions.assertEquals(1, lowerBound(a, 6))
    }

    @Test
    fun `bounds respect a narrowed window`() {
        val full = idea(1 to 1.0, 2 to 1.0, 3 to 1.0, 4 to 1.0, 5 to 1.0)
        val w = full.tail(3) // window over levels 3,4,5; start offset != 0
        // lowerBound is computed over the window only
        Assertions.assertEquals(2, lowerBound(w, 1)) // start index of window
        Assertions.assertEquals(2, lowerBound(w, 3))
        Assertions.assertEquals(5, lowerBound(w, 9)) // window end
        Assertions.assertEquals(3, upperBound(w, 3))
    }

    @Test
    fun `lowerBound equals upperBound when level absent`() {
        val a = idea(10 to 1.0, 20 to 1.0, 30 to 1.0)
        Assertions.assertEquals(lowerBound(a, 15), upperBound(a, 15))
    }

    @Test
    fun `lowerBound differs from upperBound by one when present`() {
        val a = idea(10 to 1.0, 20 to 1.0, 30 to 1.0)
        Assertions.assertEquals(lowerBound(a, 20) + 1, upperBound(a, 20))
    }

    @Test
    fun `bounds match get accessor agreement`() {
        val a = idea(2 to 5.0, 6 to 7.0, 9 to 11.0)
        // present
        val idx = lowerBound(a, 6)
        Assertions.assertEquals(7.0, a[6], DELTA)
        Assertions.assertTrue(idx in 0 until a.size)
        // absent
        Assertions.assertEquals(0.0, a[5], DELTA)
        Assertions.assertEquals(lowerBound(a, 5), upperBound(a, 5))
    }

    // =====================================================================================
    // windowLevels  (instance: shares first when full, copies the slice otherwise)
    // =====================================================================================

    private fun windowLevels(a: IntDoubleEntangledArray): IntArray =
        m("windowLevels").invoke(a) as IntArray

    @Test
    fun `windowLevels full array returns the level sequence`() {
        val a = idea(1 to 1.0, 4 to 2.0, 9 to 3.0)
        Assertions.assertArrayEquals(intArrayOf(1, 4, 9), windowLevels(a))
    }

    @Test
    fun `windowLevels of a narrowed view returns only the window slice`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 4.0, 5 to 5.0)
        Assertions.assertArrayEquals(intArrayOf(3, 4, 5), windowLevels(a.tail(3)))
        Assertions.assertArrayEquals(intArrayOf(1, 2), windowLevels(a.head(2)))
        Assertions.assertArrayEquals(intArrayOf(2, 3, 4), windowLevels(a.tail(4).head(3)))
    }

    @Test
    fun `windowLevels empty array is an empty int array`() {
        Assertions.assertArrayEquals(IntArray(0), windowLevels(EMPTY))
    }

    // =====================================================================================
    // result  (trims scratch to out entries; EMPTY singleton when nothing survives)
    // =====================================================================================

    private fun result(first: IntArray, second: DoubleArray, out: Int): IntDoubleEntangledArray =
        invokeC(cm("result", IntArray::class.java, DoubleArray::class.java, Int::class.java), first, second, out)
            as IntDoubleEntangledArray

    @Test
    fun `result with zero out returns the EMPTY singleton`() {
        Assertions.assertSame(EMPTY, result(intArrayOf(1, 2, 3), doubleArrayOf(1.0, 2.0, 3.0), 0))
    }

    @Test
    fun `result trims scratch to the out prefix`() {
        val r = result(intArrayOf(1, 2, 3, 999), doubleArrayOf(10.0, 20.0, 30.0, 0.0), 3)
        assertContentEq(listOf(1 to 10.0, 2 to 20.0, 3 to 30.0), r)
        Assertions.assertEquals(3, r.size)
    }

    @Test
    fun `result of a single entry`() {
        val r = result(intArrayOf(7, 8), doubleArrayOf(5.0, 6.0), 1)
        assertContentEq(listOf(7 to 5.0), r)
    }

    @Test
    fun `result copies so later scratch mutation does not leak`() {
        val first = intArrayOf(1, 2)
        val second = doubleArrayOf(3.0, 4.0)
        val r = result(first, second, 2)
        first[0] = 99; second[0] = 99.0
        assertContentEq(listOf(1 to 3.0, 2 to 4.0), r)
    }

    // =====================================================================================
    // collectDenseSet  (NaN = absent skipped; 0.0 dropped only when dropZeros)
    // =====================================================================================

    private fun collectDenseSet(first: IntArray, second: DoubleArray, minLevel: Int, width: Int, dropZeros: Boolean) =
        invokeC(cm("collectDenseSet", IntArray::class.java, DoubleArray::class.java, Int::class.java, Int::class.java, Boolean::class.java),
            first, second, minLevel, width, dropZeros) as IntDoubleEntangledArray

    @Test
    fun `collectDenseSet skips NaN absent slots`() {
        val second = doubleArrayOf(Double.NaN, 5.0, Double.NaN, 7.0)
        val r = collectDenseSet(IntArray(4), second, 10, 4, true)
        assertContentEq(listOf(11 to 5.0, 13 to 7.0), r)
    }

    @Test
    fun `collectDenseSet keeps genuine zeros when dropZeros false`() {
        val second = doubleArrayOf(0.0, Double.NaN, 3.0)
        val r = collectDenseSet(IntArray(3), second, 0, 3, false)
        assertContentEq(listOf(0 to 0.0, 2 to 3.0), r)
    }

    @Test
    fun `collectDenseSet drops genuine zeros when dropZeros true`() {
        val second = doubleArrayOf(0.0, Double.NaN, 3.0)
        val r = collectDenseSet(IntArray(3), second, 0, 3, true)
        assertContentEq(listOf(2 to 3.0), r)
    }

    @Test
    fun `collectDenseSet all NaN yields EMPTY`() {
        val second = doubleArrayOf(Double.NaN, Double.NaN)
        Assertions.assertSame(EMPTY, collectDenseSet(IntArray(2), second, 0, 2, true))
    }

    @Test
    fun `collectDenseSet all zero with dropZeros yields EMPTY`() {
        val second = doubleArrayOf(0.0, 0.0)
        Assertions.assertSame(EMPTY, collectDenseSet(IntArray(2), second, 0, 2, true))
    }

    @Test
    fun `collectDenseSet level reconstructed as minLevel plus index`() {
        val second = doubleArrayOf(1.0, 2.0, 3.0)
        val r = collectDenseSet(IntArray(3), second, -5, 3, true)
        assertContentEq(listOf(-5 to 1.0, -4 to 2.0, -3 to 3.0), r)
    }

    @Test
    fun `collectDenseSet negative quantities survive`() {
        val second = doubleArrayOf(-1.0, Double.NaN, -2.0)
        val r = collectDenseSet(IntArray(3), second, 0, 3, true)
        assertContentEq(listOf(0 to -1.0, 2 to -2.0), r)
    }

    // =====================================================================================
    // collectDenseSum  (absent = 0.0 additive identity; non-zero survivors only)
    // =====================================================================================

    private fun collectDenseSum(first: IntArray, second: DoubleArray, minLevel: Int, width: Int) =
        invokeC(cm("collectDenseSum", IntArray::class.java, DoubleArray::class.java, Int::class.java, Int::class.java),
            first, second, minLevel, width) as IntDoubleEntangledArray

    @Test
    fun `collectDenseSum drops zeros keeps non-zeros`() {
        val second = doubleArrayOf(0.0, 5.0, 0.0, 7.0)
        val r = collectDenseSum(IntArray(4), second, 100, 4)
        assertContentEq(listOf(101 to 5.0, 103 to 7.0), r)
    }

    @Test
    fun `collectDenseSum all zero yields EMPTY`() {
        Assertions.assertSame(EMPTY, collectDenseSum(IntArray(3), doubleArrayOf(0.0, 0.0, 0.0), 0, 3))
    }

    @Test
    fun `collectDenseSum negative totals survive`() {
        val second = doubleArrayOf(-3.0, 0.0, 4.0)
        val r = collectDenseSum(IntArray(3), second, 0, 3)
        assertContentEq(listOf(0 to -3.0, 2 to 4.0), r)
    }

    @Test
    fun `collectDenseSum width narrower than buffer ignores tail`() {
        val second = doubleArrayOf(1.0, 2.0, 999.0)
        val r = collectDenseSum(IntArray(3), second, 0, 2) // only width 2
        assertContentEq(listOf(0 to 1.0, 1 to 2.0), r)
    }

    // =====================================================================================
    // denseSet2 / mergeSet2  (the n==2 set kernels: last-writer-wins)
    // =====================================================================================

    private fun denseSet2(a: IntDoubleEntangledArray, b: IntDoubleEntangledArray, minLevel: Int, span: Int, dropZeros: Boolean): IntDoubleEntangledArray {
        val s = a.size + b.size
        return invokeC(cm("denseSet2", cls, cls, IntArray::class.java, DoubleArray::class.java, Int::class.java, Int::class.java, Boolean::class.java),
            a, b, IntArray(s), DoubleArray(span + 1), minLevel, span, dropZeros) as IntDoubleEntangledArray
    }

    private fun mergeSet2(a: IntDoubleEntangledArray, b: IntDoubleEntangledArray, dropZeros: Boolean): IntDoubleEntangledArray {
        val s = a.size + b.size
        return invokeC(cm("mergeSet2", cls, cls, IntArray::class.java, DoubleArray::class.java, Boolean::class.java),
            a, b, IntArray(s), DoubleArray(s), dropZeros) as IntDoubleEntangledArray
    }

    @Test
    fun `denseSet2 later wins on shared levels`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        val b = idea(2 to 99.0)
        assertContentEq(listOf(1 to 1.0, 2 to 99.0, 3 to 3.0), denseSet2(a, b, 1, 2, true))
    }

    @Test
    fun `denseSet2 disjoint union`() {
        val a = idea(0 to 1.0, 1 to 2.0); val b = idea(2 to 3.0, 3 to 4.0)
        assertContentEq(listOf(0 to 1.0, 1 to 2.0, 2 to 3.0, 3 to 4.0), denseSet2(a, b, 0, 3, true))
    }

    @Test
    fun `denseSet2 later clears a level to zero dropped`() {
        val a = idea(1 to 5.0, 2 to 6.0, 3 to 7.0)
        val b = idea(2 to 0.0, allowZero = true)
        assertContentEq(listOf(1 to 5.0, 3 to 7.0), denseSet2(a, b, 1, 2, true))
    }

    @Test
    fun `denseSet2 keeps overwritten zero when dropZeros false`() {
        val a = idea(1 to 5.0, 2 to 6.0)
        val b = idea(2 to 0.0, allowZero = true)
        assertContentEq(listOf(1 to 5.0, 2 to 0.0), denseSet2(a, b, 1, 1, false))
    }

    @Test
    fun `denseSet2 absent level (hole) never emitted`() {
        // levels 0 and 3 only -> hole at 1,2 stays NaN, skipped
        val a = idea(0 to 1.0); val b = idea(3 to 4.0)
        assertContentEq(listOf(0 to 1.0, 3 to 4.0), denseSet2(a, b, 0, 3, true))
    }

    @Test
    fun `denseSet2 full cancellation yields EMPTY`() {
        val a = idea(1 to 5.0, 2 to 6.0)
        val b = idea(1 to 0.0, 2 to 0.0, allowZero = true)
        Assertions.assertSame(EMPTY, denseSet2(a, b, 1, 1, true))
    }

    @Test
    fun `mergeSet2 spread later wins`() {
        val a = idea(10 to 2.0, 5000 to 3.0)
        val b = idea(10 to 9.0, 999999 to 4.0)
        assertContentEq(listOf(10 to 9.0, 5000 to 3.0, 999999 to 4.0), mergeSet2(a, b, true))
    }

    @Test
    fun `mergeSet2 disjoint union`() {
        val a = idea(10 to 1.0, 1000 to 3.0)
        val b = idea(500 to 5.0, 100000 to 4.0)
        assertContentEq(listOf(10 to 1.0, 500 to 5.0, 1000 to 3.0, 100000 to 4.0), mergeSet2(a, b, true))
    }

    @Test
    fun `mergeSet2 drops cleared shared level`() {
        val a = idea(10 to 5.0, 100000 to 7.0)
        val b = idea(100000 to 0.0, allowZero = true)
        assertContentEq(listOf(10 to 5.0), mergeSet2(a, b, true))
    }

    @Test
    fun `mergeSet2 keeps zeros when dropZeros false`() {
        val a = idea(10 to 5.0, 100000 to 7.0)
        val b = idea(100000 to 0.0, allowZero = true)
        assertContentEq(listOf(10 to 5.0, 100000 to 0.0), mergeSet2(a, b, false))
    }

    @Test
    fun `mergeSet2 a exhausted first drains b tail`() {
        val a = idea(1 to 1.0)
        val b = idea(2 to 2.0, 3 to 3.0, 4 to 4.0)
        assertContentEq(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 4.0), mergeSet2(a, b, true))
    }

    @Test
    fun `mergeSet2 b exhausted first drains a tail`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        val b = idea(0 to 9.0)
        assertContentEq(listOf(0 to 9.0, 1 to 1.0, 2 to 2.0, 3 to 3.0), mergeSet2(a, b, true))
    }

    @Test
    fun `denseSet2 matches mergeSet2 on the same contiguous input`() {
        val a = idea(1 to 1.0, 2 to 2.0, 3 to 3.0)
        val b = idea(2 to 20.0, 4 to 40.0)
        val dense = denseSet2(a, b, 1, 3, true)
        val merge = mergeSet2(a, b, true)
        assertContentEq(content(dense), merge)
    }

    // =====================================================================================
    // denseSetN / mergeSetN  (n>=3 set kernels)
    // =====================================================================================

    private fun arr(vararg a: IntDoubleEntangledArray): Array<IntDoubleEntangledArray> = arrayOf(*a)

    private fun denseSetN(arrays: Array<IntDoubleEntangledArray>, minLevel: Int, span: Int, dropZeros: Boolean): IntDoubleEntangledArray {
        val s = arrays.sumOf { it.size }
        return invokeC(cm("denseSetN", Array<IntDoubleEntangledArray>::class.java, Int::class.java, IntArray::class.java, DoubleArray::class.java, Int::class.java, Int::class.java, Boolean::class.java),
            arrays, arrays.size, IntArray(s), DoubleArray(span + 1), minLevel, span, dropZeros) as IntDoubleEntangledArray
    }

    private fun mergeSetN(arrays: Array<IntDoubleEntangledArray>, dropZeros: Boolean): IntDoubleEntangledArray {
        val s = arrays.sumOf { it.size }
        return invokeC(cm("mergeSetN", Array<IntDoubleEntangledArray>::class.java, Int::class.java, IntArray::class.java, DoubleArray::class.java, Boolean::class.java),
            arrays, arrays.size, IntArray(s), DoubleArray(s), dropZeros) as IntDoubleEntangledArray
    }

    @Test
    fun `denseSetN last writer wins per level`() {
        val arrays = arr(idea(1 to 1.0, 2 to 1.0, 3 to 1.0), idea(2 to 2.0, 3 to 2.0), idea(3 to 3.0))
        assertContentEq(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0), denseSetN(arrays, 1, 2, true))
    }

    @Test
    fun `denseSetN clear to zero dropped`() {
        val arrays = arr(idea(1 to 5.0, 2 to 6.0, 3 to 7.0), idea(1 to 0.0, allowZero = true), idea(3 to 0.0, allowZero = true))
        assertContentEq(listOf(2 to 6.0), denseSetN(arrays, 1, 2, true))
    }

    @Test
    fun `denseSetN holes never emitted`() {
        val arrays = arr(idea(0 to 1.0), idea(5 to 5.0), idea(2 to 2.0))
        assertContentEq(listOf(0 to 1.0, 2 to 2.0, 5 to 5.0), denseSetN(arrays, 0, 5, true))
    }

    @Test
    fun `denseSetN dropZeros false keeps overwritten zeros`() {
        val arrays = arr(idea(1 to 5.0), idea(1 to 0.0, allowZero = true), idea(2 to 6.0))
        assertContentEq(listOf(1 to 0.0, 2 to 6.0), denseSetN(arrays, 1, 1, false))
    }

    @Test
    fun `denseSetN all cancel yields EMPTY`() {
        val arrays = arr(idea(1 to 5.0), idea(2 to 6.0), idea(1 to 0.0, 2 to 0.0, allowZero = true))
        Assertions.assertSame(EMPTY, denseSetN(arrays, 1, 1, true))
    }

    @Test
    fun `mergeSetN spread last writer wins`() {
        val arrays = arr(idea(10 to 1.0, 100000 to 1.0), idea(10 to 2.0, 5000 to 2.0), idea(100000 to 3.0))
        assertContentEq(listOf(10 to 2.0, 5000 to 2.0, 100000 to 3.0), mergeSetN(arrays, true))
    }

    @Test
    fun `mergeSetN spread disjoint union`() {
        val arrays = arr(idea(10 to 1.0), idea(5000 to 2.0), idea(100000 to 3.0))
        assertContentEq(listOf(10 to 1.0, 5000 to 2.0, 100000 to 3.0), mergeSetN(arrays, true))
    }

    @Test
    fun `mergeSetN tie drain keeps the highest array index`() {
        // all three carry level 7; the last one (index 2) must win
        val arrays = arr(idea(7 to 1.0, 8 to 8.0), idea(7 to 2.0), idea(7 to 3.0, 9 to 9.0))
        assertContentEq(listOf(7 to 3.0, 8 to 8.0, 9 to 9.0), mergeSetN(arrays, true))
    }

    @Test
    fun `mergeSetN clear all yields EMPTY`() {
        val arrays = arr(idea(1 to 1.0, 100000 to 1.0), idea(1 to 2.0, 50000 to 2.0),
            idea(1 to 0.0, 50000 to 0.0, 100000 to 0.0, allowZero = true))
        Assertions.assertSame(EMPTY, mergeSetN(arrays, true))
    }

    @Test
    fun `mergeSetN many identical spread arrays equal one`() {
        val a = idea(10 to 1.0, 100000 to 2.0)
        assertContentEq(listOf(10 to 1.0, 100000 to 2.0), mergeSetN(arr(a, a, a, a, a, a), true))
    }

    @Test
    fun `mergeSetN extreme span no overflow`() {
        val arrays = arr(idea(Int.MIN_VALUE to 1.0, 0 to 1.0, Int.MAX_VALUE to 1.0), idea(0 to 2.0), idea(Int.MAX_VALUE to 3.0))
        assertContentEq(listOf(Int.MIN_VALUE to 1.0, 0 to 2.0, Int.MAX_VALUE to 3.0), mergeSetN(arrays, true))
    }

    @Test
    fun `denseSetN matches mergeSetN on same input`() {
        val arrays = arr(idea(1 to 1.0, 4 to 4.0), idea(2 to 2.0, 4 to 40.0), idea(3 to 3.0))
        assertContentEq(content(denseSetN(arrays, 1, 3, true)), mergeSetN(arrays, true))
    }

    // =====================================================================================
    // denseSum2 / mergeSum2  (weighted n==2)
    // =====================================================================================

    private fun denseSum2(a: IntDoubleEntangledArray, b: IntDoubleEntangledArray, mA: Double, mB: Double, minLevel: Int, span: Int): IntDoubleEntangledArray {
        val s = a.size + b.size
        return invokeC(cm("denseSum2", cls, cls, Double::class.java, Double::class.java, IntArray::class.java, DoubleArray::class.java, Int::class.java, Int::class.java),
            a, b, mA, mB, IntArray(s), DoubleArray(span + 1), minLevel, span) as IntDoubleEntangledArray
    }

    private fun mergeSum2(a: IntDoubleEntangledArray, b: IntDoubleEntangledArray, mA: Double, mB: Double): IntDoubleEntangledArray {
        val s = a.size + b.size
        return invokeC(cm("mergeSum2", cls, cls, Double::class.java, Double::class.java, IntArray::class.java, DoubleArray::class.java),
            a, b, mA, mB, IntArray(s), DoubleArray(s)) as IntDoubleEntangledArray
    }

    @Test
    fun `denseSum2 sums weighted shared levels`() {
        val a = idea(1 to 2.0, 2 to 3.0, 3 to 4.0)
        val b = idea(1 to 1.0, 2 to 1.0, 3 to 1.0)
        assertContentEq(listOf(1 to 7.0, 2 to 9.0, 3 to 11.0), denseSum2(a, b, 2.0, 3.0, 1, 2))
    }

    @Test
    fun `denseSum2 cancellation drops level`() {
        val a = idea(1 to 2.0, 2 to 3.0)
        val b = idea(1 to 2.0, 2 to 1.0)
        assertContentEq(listOf(2 to 2.0), denseSum2(a, b, 1.0, -1.0, 1, 1))
    }

    @Test
    fun `denseSum2 disjoint with holes`() {
        val a = idea(0 to 2.0, 3 to 5.0); val b = idea(1 to 1.0)
        assertContentEq(listOf(0 to 4.0, 1 to 1.0, 3 to 10.0), denseSum2(a, b, 2.0, 1.0, 0, 3))
    }

    @Test
    fun `mergeSum2 spread sums shared`() {
        val a = idea(10 to 2.0, 5000 to 3.0)
        val b = idea(10 to 1.0, 999999 to 4.0)
        assertContentEq(listOf(10 to 5.0, 5000 to 6.0, 999999 to 4.0), mergeSum2(a, b, 2.0, 1.0))
    }

    @Test
    fun `mergeSum2 spread cancellation drops shared`() {
        val a = idea(10 to 2.0, 5000 to 3.0)
        val b = idea(10 to 2.0, 999999 to 4.0)
        assertContentEq(listOf(5000 to 3.0, 999999 to -4.0), mergeSum2(a, b, 1.0, -1.0))
    }

    @Test
    fun `mergeSum2 a tail then b tail`() {
        val a = idea(1 to 1.0, 2 to 2.0)
        val b = idea(3 to 3.0, 4 to 4.0)
        assertContentEq(listOf(1 to 1.0, 2 to 2.0, 3 to 3.0, 4 to 4.0), mergeSum2(a, b, 1.0, 1.0))
    }

    @Test
    fun `denseSum2 matches mergeSum2 on same input`() {
        val a = idea(1 to 2.0, 2 to 3.0, 5 to 4.0)
        val b = idea(2 to 5.0, 5 to 6.0)
        assertContentEq(content(denseSum2(a, b, 2.0, -1.0, 1, 4)), mergeSum2(a, b, 2.0, -1.0))
    }

    // =====================================================================================
    // denseSumN / mergeSumN  (weighted n>=3)
    // =====================================================================================

    private fun denseSumN(arrays: Array<IntDoubleEntangledArray>, mult: DoubleArray, minLevel: Int, span: Int): IntDoubleEntangledArray {
        val s = arrays.sumOf { it.size }
        return invokeC(cm("denseSumN", Array<IntDoubleEntangledArray>::class.java, DoubleArray::class.java, IntArray::class.java, DoubleArray::class.java, Int::class.java, Int::class.java),
            arrays, mult, IntArray(s), DoubleArray(span + 1), minLevel, span) as IntDoubleEntangledArray
    }

    private fun mergeSumN(arrays: Array<IntDoubleEntangledArray>, mult: DoubleArray): IntDoubleEntangledArray {
        val s = arrays.sumOf { it.size }
        return invokeC(cm("mergeSumN", Array<IntDoubleEntangledArray>::class.java, DoubleArray::class.java, IntArray::class.java, DoubleArray::class.java),
            arrays, mult, IntArray(s), DoubleArray(s)) as IntDoubleEntangledArray
    }

    @Test
    fun `denseSumN weighted overlap`() {
        val arrays = arr(idea(1 to 1.0, 2 to 2.0), idea(1 to 2.0, 2 to 3.0), idea(1 to 1.0, 2 to 1.0))
        assertContentEq(listOf(1 to 8.0, 2 to 11.0), denseSumN(arrays, doubleArrayOf(1.0, 2.0, 3.0), 1, 1))
    }

    @Test
    fun `denseSumN cancellation drops level`() {
        val arrays = arr(idea(1 to 2.0, 2 to 3.0, 3 to 4.0), idea(1 to 1.0, 2 to 3.0, 3 to 1.0), idea(1 to 1.0))
        assertContentEq(listOf(3 to 3.0), denseSumN(arrays, doubleArrayOf(1.0, -1.0, -1.0), 1, 2))
    }

    @Test
    fun `denseSumN holes`() {
        val arrays = arr(idea(0 to 1.0), idea(5 to 3.0), idea(0 to 1.0, 5 to 1.0))
        assertContentEq(listOf(0 to 2.0, 5 to 4.0), denseSumN(arrays, doubleArrayOf(1.0, 1.0, 1.0), 0, 5))
    }

    @Test
    fun `mergeSumN spread sums`() {
        val arrays = arr(idea(10 to 1.0, 100000 to 2.0), idea(500 to 1.0, 100000 to 3.0), idea(10 to 4.0, 9000 to 1.0))
        assertContentEq(listOf(10 to 5.0, 500 to 1.0, 9000 to 1.0, 100000 to 5.0), mergeSumN(arrays, doubleArrayOf(1.0, 1.0, 1.0)))
    }

    @Test
    fun `mergeSumN weighted with negative multipliers`() {
        val arrays = arr(idea(10 to 2.0, 5000 to 1.0), idea(10 to 2.0, 100000 to 1.0), idea(5000 to 1.0))
        assertContentEq(listOf(100000 to -1.0), mergeSumN(arrays, doubleArrayOf(1.0, -1.0, -1.0)))
    }

    @Test
    fun `mergeSumN tie drain sums every head on the level`() {
        val arrays = arr(idea(7 to 1.0), idea(7 to 2.0), idea(7 to 3.0))
        assertContentEq(listOf(7 to 6.0), mergeSumN(arrays, doubleArrayOf(1.0, 1.0, 1.0)))
    }

    @Test
    fun `denseSumN matches mergeSumN on same input`() {
        val arrays = arr(idea(1 to 1.0, 5 to 2.0), idea(2 to 3.0, 5 to 1.0), idea(3 to 1.0))
        val mult = doubleArrayOf(2.0, 1.0, -1.0)
        assertContentEq(content(denseSumN(arrays, mult, 1, 4)), mergeSumN(arrays, mult))
    }

    // =====================================================================================
    // denseSumOf2 / mergeSumOf2  (unit-multiplier n==2 sum)
    // =====================================================================================

    private fun denseSumOf2(a: IntDoubleEntangledArray, b: IntDoubleEntangledArray, minLevel: Int, span: Int): IntDoubleEntangledArray {
        val s = a.size + b.size
        return invokeC(cm("denseSumOf2", cls, cls, IntArray::class.java, DoubleArray::class.java, Int::class.java, Int::class.java),
            a, b, IntArray(s), DoubleArray(span + 1), minLevel, span) as IntDoubleEntangledArray
    }

    private fun mergeSumOf2(a: IntDoubleEntangledArray, b: IntDoubleEntangledArray): IntDoubleEntangledArray {
        val s = a.size + b.size
        return invokeC(cm("mergeSumOf2", cls, cls, IntArray::class.java, DoubleArray::class.java),
            a, b, IntArray(s), DoubleArray(s)) as IntDoubleEntangledArray
    }

    @Test
    fun `denseSumOf2 sums shared levels`() {
        val a = idea(1 to 1.0, 2 to 2.0); val b = idea(2 to 3.0, 3 to 3.0)
        assertContentEq(listOf(1 to 1.0, 2 to 5.0, 3 to 3.0), denseSumOf2(a, b, 1, 2))
    }

    @Test
    fun `denseSumOf2 cancellation drops`() {
        val a = idea(1 to 2.0, 2 to 3.0); val b = idea(1 to -2.0)
        assertContentEq(listOf(2 to 3.0), denseSumOf2(a, b, 1, 1))
    }

    @Test
    fun `mergeSumOf2 spread sums`() {
        val a = idea(1 to 1.0); val b = idea(1000 to 2.0)
        assertContentEq(listOf(1 to 1.0, 1000 to 2.0), mergeSumOf2(a, b))
    }

    @Test
    fun `mergeSumOf2 overlap and tails`() {
        val a = idea(10 to 1.0, 5000 to 2.0)
        val b = idea(10 to 4.0, 999999 to 9.0)
        assertContentEq(listOf(10 to 5.0, 5000 to 2.0, 999999 to 9.0), mergeSumOf2(a, b))
    }

    @Test
    fun `denseSumOf2 matches mergeSumOf2`() {
        val a = idea(1 to 1.0, 2 to 2.0, 4 to 4.0)
        val b = idea(2 to 20.0, 4 to 40.0)
        assertContentEq(content(denseSumOf2(a, b, 1, 3)), mergeSumOf2(a, b))
    }

    // =====================================================================================
    // denseSumOfN / mergeSumOfN  (unit-multiplier n>=3 sum)
    // =====================================================================================

    private fun denseSumOfN(arrays: Array<IntDoubleEntangledArray>, minLevel: Int, span: Int): IntDoubleEntangledArray {
        val s = arrays.sumOf { it.size }
        return invokeC(cm("denseSumOfN", Array<IntDoubleEntangledArray>::class.java, IntArray::class.java, DoubleArray::class.java, Int::class.java, Int::class.java),
            arrays, IntArray(s), DoubleArray(span + 1), minLevel, span) as IntDoubleEntangledArray
    }

    private fun mergeSumOfN(arrays: Array<IntDoubleEntangledArray>): IntDoubleEntangledArray {
        val s = arrays.sumOf { it.size }
        return invokeC(cm("mergeSumOfN", Array<IntDoubleEntangledArray>::class.java, IntArray::class.java, DoubleArray::class.java),
            arrays, IntArray(s), DoubleArray(s)) as IntDoubleEntangledArray
    }

    @Test
    fun `denseSumOfN sums three overlapping`() {
        val arrays = arr(idea(1 to 1.0, 2 to 1.0), idea(1 to 1.0, 3 to 1.0), idea(1 to 1.0, 2 to 1.0))
        assertContentEq(listOf(1 to 3.0, 2 to 2.0, 3 to 1.0), denseSumOfN(arrays, 1, 2))
    }

    @Test
    fun `denseSumOfN cancellation`() {
        val arrays = arr(idea(1 to 2.0), idea(1 to 3.0), idea(1 to -5.0, 2 to 1.0))
        assertContentEq(listOf(2 to 1.0), denseSumOfN(arrays, 1, 1))
    }

    @Test
    fun `mergeSumOfN spread sums`() {
        val arrays = arr(idea(0 to 1.0), idea(500 to 2.0), idea(1000 to 3.0))
        assertContentEq(listOf(0 to 1.0, 500 to 2.0, 1000 to 3.0), mergeSumOfN(arrays))
    }

    @Test
    fun `mergeSumOfN spread overlap tie drain`() {
        val arrays = arr(idea(10 to 1.0, 100000 to 1.0), idea(10 to 2.0, 100000 to 2.0), idea(10 to 3.0))
        assertContentEq(listOf(10 to 6.0, 100000 to 3.0), mergeSumOfN(arrays))
    }

    @Test
    fun `denseSumOfN matches mergeSumOfN`() {
        val arrays = arr(idea(1 to 1.0, 5 to 2.0), idea(2 to 3.0, 5 to 1.0), idea(3 to 4.0))
        assertContentEq(content(denseSumOfN(arrays, 1, 4)), mergeSumOfN(arrays))
    }

    // =====================================================================================
    // cumulativeSet1 / weightedSum1 / sum1  (the n==1 fast kernels)
    // =====================================================================================

    private fun cumulativeSet1(a: IntDoubleEntangledArray, dropZeros: Boolean): IntDoubleEntangledArray =
        invokeC(cm("cumulativeSet1", cls, IntArray::class.java, DoubleArray::class.java, Boolean::class.java),
            a, IntArray(a.size), DoubleArray(a.size), dropZeros) as IntDoubleEntangledArray

    private fun weightedSum1(a: IntDoubleEntangledArray, mult: Double): IntDoubleEntangledArray =
        invokeC(cm("weightedSum1", cls, Double::class.java, IntArray::class.java, DoubleArray::class.java),
            a, mult, IntArray(a.size), DoubleArray(a.size)) as IntDoubleEntangledArray

    private fun sum1(a: IntDoubleEntangledArray): IntDoubleEntangledArray =
        invokeC(cm("sum1", cls, IntArray::class.java, DoubleArray::class.java),
            a, IntArray(a.size), DoubleArray(a.size)) as IntDoubleEntangledArray

    @Test
    fun `cumulativeSet1 returns same instance when nothing dropped`() {
        val a = idea(1 to 2.0, 5 to 3.0)
        Assertions.assertSame(a, cumulativeSet1(a, true))
    }

    @Test
    fun `cumulativeSet1 drops zeros when dropZeros true`() {
        val a = idea(1 to 0.0, 2 to 5.0, 3 to 0.0, allowZero = true)
        assertContentEq(listOf(2 to 5.0), cumulativeSet1(a, true))
    }

    @Test
    fun `cumulativeSet1 keeps zeros when dropZeros false`() {
        val a = idea(1 to 0.0, 2 to 5.0, allowZero = true)
        // out == array.size -> same instance returned
        Assertions.assertSame(a, cumulativeSet1(a, false))
    }

    @Test
    fun `weightedSum1 multiplier one all survive returns same instance`() {
        val a = idea(1 to 2.0, 2 to 3.0)
        Assertions.assertSame(a, weightedSum1(a, 1.0))
    }

    @Test
    fun `weightedSum1 scales`() {
        assertContentEq(listOf(1 to 4.0, 2 to 6.0), weightedSum1(idea(1 to 2.0, 2 to 3.0), 2.0))
    }

    @Test
    fun `weightedSum1 drops zeros produced by source zeros`() {
        val a = idea(1 to 2.0, 2 to 0.0, 3 to 4.0, allowZero = true)
        assertContentEq(listOf(1 to 6.0, 3 to 12.0), weightedSum1(a, 3.0))
    }

    @Test
    fun `weightedSum1 all-zero source yields EMPTY`() {
        Assertions.assertSame(EMPTY, weightedSum1(idea(1 to 0.0, 2 to 0.0, allowZero = true), 5.0))
    }

    @Test
    fun `sum1 returns same instance for canonical input`() {
        val a = idea(1 to 2.0, 2 to 3.0)
        Assertions.assertSame(a, sum1(a))
    }

    @Test
    fun `sum1 drops zeros`() {
        val a = idea(1 to 2.0, 2 to 0.0, 3 to 4.0, allowZero = true)
        assertContentEq(listOf(1 to 2.0, 3 to 4.0), sum1(a))
    }

    @Test
    fun `sum1 all-zero yields EMPTY`() {
        Assertions.assertSame(EMPTY, sum1(idea(1 to 0.0, 2 to 0.0, allowZero = true)))
    }

    // =====================================================================================
    // PERFORMANCE + MEMORY  (allocation / no-gross-regression; finishes in a few seconds)
    // =====================================================================================

    private val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private fun bytesPerOp(iterations: Int, op: (Int) -> Unit): Double {
        val tid = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(tid)
        for (i in 0 until iterations) op(i)
        val bytes = bean.getThreadAllocatedBytes(tid) - before
        return bytes.toDouble() / iterations
    }

    private fun bigArray(n: Int, base: Int = 0) =
        IntDoubleEntangledArray((0 until n).map { (base + it) to (it + 1).toDouble() }, allowZero = false)

    @Test
    fun `perf times by zero and one fast paths allocate nothing`() {
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        val a = bigArray(1000)
        val op: (Int) -> Unit = { a.times(0.0); a.times(1.0); a.div(1.0) }
        repeat(50_000) { op(it) }
        val bytes = bytesPerOp(500_000, op)
        println("times/div fast-path bytes/op = ${"%.3f".format(bytes)}")
        // EMPTY singleton + same-instance returns: no per-op allocation of any size.
        Assertions.assertTrue(bytes < 16.0, "fast paths should allocate ~nothing: $bytes B/op")
    }

    @Test
    fun `perf construction allocation is bounded`() {
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        val pairs = (0 until 256).map { it to (it + 1).toDouble() }
        val op: (Int) -> Unit = { IntDoubleEntangledArray(pairs, allowZero = false) }
        repeat(20_000) { op(it) }
        val bytes = bytesPerOp(200_000, op)
        println("construction(256) bytes/op = ${"%.1f".format(bytes)}")
        // Two trimmed backing arrays (int+double) of 256 dominate ~ 256*(4+8) = ~3KB; allow generous slack.
        Assertions.assertTrue(bytes < 64_000.0, "construction allocation should be bounded: $bytes B/op")
    }

    @Test
    fun `perf weightedSum large dense completes and is bounded`() {
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        val a = bigArray(2000); val b = bigArray(2000)
        val op: (Int) -> Unit = { ws(a to 2.0, b to 3.0) }
        repeat(2_000) { op(it) }
        val t0 = System.nanoTime()
        val bytes = bytesPerOp(20_000, op)
        val ms = (System.nanoTime() - t0) / 1e6
        println("weightedSum(2x2000 dense) bytes/op = ${"%.0f".format(bytes)}  total ${"%.0f".format(ms)}ms")
        Assertions.assertTrue(ms < 4000.0, "should finish quickly: ${ms}ms")
        // result is at most ~2000 entries -> two trimmed arrays; scratch is reused, not per-op.
        Assertions.assertTrue(bytes < 200_000.0, "weightedSum allocation should be bounded: $bytes B/op")
    }

    @Test
    fun `perf cumulativeSet large completes quickly`() {
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        val arrays = (0 until 8).map { bigArray(500) }
        val op: (Int) -> Unit = { IntDoubleEntangledArray.cumulativeSet(arrays) }
        repeat(2_000) { op(it) }
        val t0 = System.nanoTime()
        repeat(20_000) { op(it) }
        val ms = (System.nanoTime() - t0) / 1e6
        println("cumulativeSet(8x500) total ${"%.0f".format(ms)}ms")
        Assertions.assertTrue(ms < 4000.0, "should finish quickly: ${ms}ms")
    }

    @Test
    fun `perf absoluteDiff large completes quickly`() {
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        val a = bigArray(3000); val b = bigArray(3000, base = 1)
        val op: (Int) -> Unit = { IntDoubleEntangledArray.absoluteDiff(a, b) }
        repeat(2_000) { op(it) }
        val t0 = System.nanoTime()
        repeat(20_000) { op(it) }
        val ms = (System.nanoTime() - t0) / 1e6
        println("absoluteDiff(2x3000) total ${"%.0f".format(ms)}ms")
        Assertions.assertTrue(ms < 4000.0, "should finish quickly: ${ms}ms")
    }

    @Test
    fun `perf serialize deserialize round trip completes quickly`() {
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        val a = bigArray(2000) // one big contiguous packet
        val op: (Int) -> Unit = { IntDoubleEntangledArray.deserialize(a.serialize()) }
        repeat(2_000) { op(it) }
        val t0 = System.nanoTime()
        repeat(20_000) { op(it) }
        val ms = (System.nanoTime() - t0) / 1e6
        println("serialize+deserialize(2000) total ${"%.0f".format(ms)}ms")
        Assertions.assertTrue(ms < 4000.0, "should finish quickly: ${ms}ms")
        // sanity: round-trip preserves content
        val rt = IntDoubleEntangledArray.deserialize(a.serialize())
        Assertions.assertEquals(a.size, rt.size)
        Assertions.assertSame(EMPTY, ws(rt to 1.0, a to -1.0))
    }

    // =====================================================================================
    // DOUBLE-SPECIFIC public edge states (NaN / Infinity / -0.0 / tiny / huge)
    // =====================================================================================

    @Test
    fun `times by NaN propagates NaN quantities (kept, not dropped)`() {
        val a = idea(1 to 2.0, 2 to 3.0)
        val r = a.times(Double.NaN)
        Assertions.assertEquals(2, r.size)
        Assertions.assertTrue(content(r).all { it.second.isNaN() })
    }

    @Test
    fun `times by infinity yields infinite quantities`() {
        val a = idea(1 to 2.0, 2 to -3.0)
        val r = a.times(Double.POSITIVE_INFINITY)
        val c = content(r)
        Assertions.assertEquals(Double.POSITIVE_INFINITY, c[0].second)
        Assertions.assertEquals(Double.NEGATIVE_INFINITY, c[1].second)
    }

    @Test
    fun `times by negative zero is treated as zero - returns EMPTY`() {
        // -0.0 == 0.0 is true, so the zero-multiplier fast path triggers.
        Assertions.assertSame(EMPTY, idea(1 to 2.0).times(-0.0))
    }

    @Test
    fun `div by zero yields infinities`() {
        val a = idea(1 to 2.0, 2 to -3.0)
        val r = a.div(0.0)
        val c = content(r)
        Assertions.assertEquals(Double.POSITIVE_INFINITY, c[0].second)
        Assertions.assertEquals(Double.NEGATIVE_INFINITY, c[1].second)
    }

    @Test
    fun `div by infinity yields signed zeros (kept as entries)`() {
        val a = idea(1 to 2.0, 2 to 3.0)
        val r = a.div(Double.POSITIVE_INFINITY)
        // 2.0 / inf = 0.0 ; entries are NOT dropped by div (only EMPTY/1.0 shortcuts)
        Assertions.assertEquals(2, r.size)
        Assertions.assertTrue(content(r).all { it.second == 0.0 })
    }

    @Test
    fun `tiny and huge quantities survive construction and round-trip`() {
        val a = idea(1 to Double.MIN_VALUE, 2 to Double.MAX_VALUE, 3 to 1e300)
        Assertions.assertEquals(3, a.size)
        val rt = IntDoubleEntangledArray.deserialize(a.serialize())
        Assertions.assertEquals(Double.MIN_VALUE, rt[1], 0.0)
        Assertions.assertEquals(Double.MAX_VALUE, rt[2], 0.0)
        Assertions.assertEquals(1e300, rt[3], 0.0)
    }

    @Test
    fun `serialize round-trips NaN and infinities bit-exactly`() {
        val a = idea(1 to Double.NaN, 2 to Double.POSITIVE_INFINITY, 3 to Double.NEGATIVE_INFINITY)
        val rt = IntDoubleEntangledArray.deserialize(a.serialize())
        Assertions.assertTrue(rt[1].isNaN())
        Assertions.assertEquals(Double.POSITIVE_INFINITY, rt[2])
        Assertions.assertEquals(Double.NEGATIVE_INFINITY, rt[3])
    }

    @Test
    fun `negative zero quantity is treated as zero by weightedSum (dropped)`() {
        // -0.0 != 0.0 is false, so a -0.0 result is dropped like +0.0.
        val a = idea(1 to 0.0, 2 to 5.0, allowZero = true)
        val r = ws(a to -1.0) // level 1: -0.0, dropped; level 2: -5.0
        assertContentEq(listOf(2 to -5.0), r)
    }

    @Test
    fun `construction with negative zero fuses like positive zero`() {
        // -0.0 added to +0.0 -> 0.0; allowZero=false drops it.
        val a = idea(5 to -0.0, 5 to 0.0, 6 to 1.0, allowZero = false)
        assertContentEq(listOf(6 to 1.0), a)
    }

    @Test
    fun `get on huge level span uses binary search correctly`() {
        val a = idea(Int.MIN_VALUE to 1.0, 0 to 2.0, Int.MAX_VALUE to 3.0)
        Assertions.assertEquals(1.0, a[Int.MIN_VALUE], 0.0)
        Assertions.assertEquals(2.0, a[0], 0.0)
        Assertions.assertEquals(3.0, a[Int.MAX_VALUE], 0.0)
        Assertions.assertEquals(0.0, a[1], 0.0)
    }
}
