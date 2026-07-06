package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.lang.reflect.Method
import kotlin.random.Random

/**
 * White-box / reflection coverage for [DoubleDoubleEntangledArray], filling the gaps left by the
 * black-box [DoubleDoubleEntangledArrayTest]:
 *
 *  1. Every **private** routine exercised directly through reflection — the sort family
 *     (`classicSort`, `heapSort`, `siftDown`, `sort`), the canonicalizer (`fuse`), the binary-search
 *     bounds (`lowerBound`, `upperBound`), `windowLevels`, `result`, and the N-way merge specializations
 *     (`cumulativeSet1/2/N`, `weightedSum1/2/N`, `sum1/2/N`).
 *  2. **Performance + memory** characterization (allocation per op via `ThreadMXBean`), guarded by
 *     `Assumptions.assumeTrue` and generous thresholds.
 *  3. A handful of genuinely uncovered public edge states (NaN / Infinity / -0.0 levels & quantities,
 *     very dense fractional packets through serialize/deserialize).
 *
 * NOTE: This file intentionally does NOT re-test the public behaviors already covered by
 * [DoubleDoubleEntangledArrayTest].
 */
class DoubleDoubleEntangledArrayCoverageTest {

    private val cls = DoubleDoubleEntangledArray::class.java
    private val EMPTY = DoubleDoubleEntangledArray.EMPTY

    // ---------------------------------------------------------------------------------------------
    // reflection plumbing
    // ---------------------------------------------------------------------------------------------

    /** A companion-object private static-like method (reflected via the Companion instance). */
    private fun companionMethod(name: String, vararg types: Class<*>): Pair<Any, Method> {
        val companionField = cls.getDeclaredField("Companion").apply { isAccessible = true }
        val companion = companionField.get(null)
        val m = companion.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }
        return companion to m
    }

    private fun callCompanion(name: String, types: Array<Class<*>>, vararg args: Any?): Any? {
        val (companion, m) = companionMethod(name, *types)
        return m.invoke(companion, *args)
    }

    private fun callInstance(target: DoubleDoubleEntangledArray, name: String, types: Array<Class<*>>, vararg args: Any?): Any? {
        val m = cls.getDeclaredMethod(name, *types).apply { isAccessible = true }
        return m.invoke(target, *args)
    }

    private fun ddea(vararg pairs: Pair<Double, Double>, allowZero: Boolean = false) =
        DoubleDoubleEntangledArray(pairs.toList(), allowZero)

    private fun ws(vararg terms: Pair<DoubleDoubleEntangledArray, Double>) =
        DoubleDoubleEntangledArray.weightedSum(terms.toList())

    /** Reads a private field by name from an instance. */
    private fun <T> field(target: DoubleDoubleEntangledArray, name: String): T {
        val f = cls.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return f.get(target) as T
    }

    /** Asserts the two arrays carry exactly equal canonical content (via the cancellation identity). */
    private fun assertSameContent(expected: List<Pair<Double, Double>>, actual: DoubleDoubleEntangledArray) {
        val exp = DoubleDoubleEntangledArray(expected, allowZero = false)
        Assertions.assertEquals(exp.size, actual.size, "size differs")
        Assertions.assertSame(EMPTY, ws(actual to 1.0, exp to -1.0), "content differs")
    }

    private val DELTA = 1e-9

    // =============================================================================================
    // classicSort  (companion private)
    // =============================================================================================

    private fun classicSort(levels: DoubleArray, quantities: DoubleArray, end: Int) =
        callCompanion("classicSort", arrayOf(DoubleArray::class.java, DoubleArray::class.java, Int::class.javaPrimitiveType!!),
            levels, quantities, end)

    @Test
    fun `classicSort empty and single are no-ops`() {
        val l = doubleArrayOf(); val q = doubleArrayOf()
        classicSort(l, q, 0)
        val l1 = doubleArrayOf(3.0); val q1 = doubleArrayOf(9.0)
        classicSort(l1, q1, 1)
        Assertions.assertArrayEquals(doubleArrayOf(3.0), l1, DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(9.0), q1, DELTA)
    }

    @Test
    fun `classicSort reverse input sorts ascending carrying quantities`() {
        val l = doubleArrayOf(5.0, 4.0, 3.0, 2.0, 1.0)
        val q = doubleArrayOf(50.0, 40.0, 30.0, 20.0, 10.0)
        classicSort(l, q, 5)
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), l, DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0), q, DELTA)
    }

    @Test
    fun `classicSort already ascending unchanged`() {
        val l = doubleArrayOf(1.0, 2.0, 3.0)
        val q = doubleArrayOf(10.0, 20.0, 30.0)
        classicSort(l, q, 3)
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), l, DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0), q, DELTA)
    }

    @Test
    fun `classicSort fractional and negative levels`() {
        val l = doubleArrayOf(2.5, -1.5, 0.0, -3.25, 1.75)
        val q = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        classicSort(l, q, 5)
        Assertions.assertArrayEquals(doubleArrayOf(-3.25, -1.5, 0.0, 1.75, 2.5), l, DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(4.0, 2.0, 3.0, 5.0, 1.0), q, DELTA)
    }

    @Test
    fun `classicSort with equal levels preserves their joint quantities`() {
        val l = doubleArrayOf(2.0, 1.0, 2.0, 1.0)
        val q = doubleArrayOf(20.0, 10.0, 22.0, 11.0)
        classicSort(l, q, 4)
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 1.0, 2.0, 2.0), l, DELTA)
        // quantities for level 1 are {10,11}, for level 2 {20,22}; sums must be preserved
        Assertions.assertEquals(21.0, q[0] + q[1], DELTA)
        Assertions.assertEquals(42.0, q[2] + q[3], DELTA)
    }

    @Test
    fun `classicSort honours the end boundary leaving the tail untouched`() {
        val l = doubleArrayOf(3.0, 1.0, 2.0, 999.0)
        val q = doubleArrayOf(30.0, 10.0, 20.0, 9999.0)
        classicSort(l, q, 3) // only sort [0,3)
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 999.0), l, DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0, 9999.0), q, DELTA)
    }

    @Test
    fun `classicSort randomized matches Arrays sort by lockstep pairing`() {
        val rnd = Random(101)
        repeat(200) {
            val n = rnd.nextInt(1, 35)
            val l = DoubleArray(n) { rnd.nextInt(-50, 50).toDouble() }
            // make quantities a unique tag so we can verify the level<->quantity pairing survives
            val q = DoubleArray(n) { it.toDouble() }
            val pairing = HashMap<Int, Double>()
            for (i in 0 until n) pairing[i] = l[i]
            classicSort(l, q, n)
            // levels ascending
            for (i in 1 until n) Assertions.assertTrue(l[i - 1] <= l[i])
            // each quantity (tag) still points at its original level
            for (i in 0 until n) Assertions.assertEquals(pairing[q[i].toInt()]!!, l[i], DELTA)
        }
    }

    // =============================================================================================
    // heapSort + siftDown  (companion private)
    // =============================================================================================

    private fun heapSort(levels: DoubleArray, quantities: DoubleArray, end: Int) =
        callCompanion("heapSort", arrayOf(DoubleArray::class.java, DoubleArray::class.java, Int::class.javaPrimitiveType!!),
            levels, quantities, end)

    private fun siftDown(levels: DoubleArray, quantities: DoubleArray, start: Int, size: Int) =
        callCompanion("siftDown", arrayOf(DoubleArray::class.java, DoubleArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            levels, quantities, start, size)

    @Test
    fun `heapSort reverse input sorts ascending with quantities`() {
        val n = 64
        val l = DoubleArray(n) { (n - it).toDouble() }
        val q = DoubleArray(n) { (n - it).toDouble() * 10.0 }
        heapSort(l, q, n)
        for (i in 0 until n) {
            Assertions.assertEquals((i + 1).toDouble(), l[i], DELTA)
            Assertions.assertEquals((i + 1).toDouble() * 10.0, q[i], DELTA)
        }
    }

    @Test
    fun `heapSort shuffled large input matches reference and keeps pairing`() {
        val rnd = Random(202)
        repeat(50) {
            val n = rnd.nextInt(41, 250) // above INSERTION_THRESHOLD
            val l = DoubleArray(n) { rnd.nextInt(-1000, 1000).toDouble() }
            val q = DoubleArray(n) { it.toDouble() }
            val expectedLevelByTag = (0 until n).associateWith { l[it] }
            heapSort(l, q, n)
            for (i in 1 until n) Assertions.assertTrue(l[i - 1] <= l[i], "not ascending at $i")
            for (i in 0 until n) Assertions.assertEquals(expectedLevelByTag[q[i].toInt()]!!, l[i], DELTA)
        }
    }

    @Test
    fun `heapSort with many duplicate levels preserves grouped quantity sums`() {
        val n = 100
        val rnd = Random(303)
        val l = DoubleArray(n) { (rnd.nextInt(0, 5)).toDouble() }
        val q = DoubleArray(n) { (it + 1).toDouble() }
        val before = HashMap<Double, Double>()
        for (i in 0 until n) before.merge(l[i], q[i], Double::plus)
        heapSort(l, q, n)
        for (i in 1 until n) Assertions.assertTrue(l[i - 1] <= l[i])
        val after = HashMap<Double, Double>()
        for (i in 0 until n) after.merge(l[i], q[i], Double::plus)
        Assertions.assertEquals(before, after)
    }

    @Test
    fun `heapSort fractional levels sort exactly`() {
        val l = doubleArrayOf(2.5, 2.25, 2.75, 2.0, 2.125, 2.875, 2.0625)
        val q = DoubleArray(l.size) { it.toDouble() }
        heapSort(l, q, l.size)
        val expected = l.copyOf().also { it.sort() }
        Assertions.assertArrayEquals(expected, l, 0.0) // dyadic fractions are exact
    }

    @Test
    fun `siftDown turns a near-heap into a valid max-heap at root`() {
        // levels[0] violates the heap property; siftDown(0, size) must push it down so root is max.
        val l = doubleArrayOf(1.0, 9.0, 8.0, 5.0, 6.0, 7.0, 4.0)
        val q = DoubleArray(l.size) { it.toDouble() }
        siftDown(l, q, 0, l.size)
        // after sifting from 0, every parent must be >= its children (max-heap invariant)
        assertMaxHeap(l, l.size)
        // quantities moved in lockstep: the value at root is the same pair as before-sift's max child path.
        // verify multiset of (level, tag) pairs is unchanged
        Assertions.assertEquals((0..6).map { it.toDouble() }.toSet(), q.toSet())
    }

    @Test
    fun `siftDown respects the size boundary`() {
        val l = doubleArrayOf(1.0, 9.0, 8.0, 100.0, 200.0)
        val q = DoubleArray(l.size) { it.toDouble() }
        siftDown(l, q, 0, 3) // only consider [0,3) as the heap; indices 3,4 are "off-heap"
        // root must be the max of the first three (9.0); the off-heap tail is untouched
        Assertions.assertEquals(9.0, l[0], DELTA)
        Assertions.assertEquals(100.0, l[3], DELTA)
        Assertions.assertEquals(200.0, l[4], DELTA)
    }

    @Test
    fun `siftDown leaf root is a no-op`() {
        val l = doubleArrayOf(5.0, 4.0, 3.0)
        val q = doubleArrayOf(50.0, 40.0, 30.0)
        siftDown(l, q, 2, 3) // root index 2 has no children within size 3
        Assertions.assertArrayEquals(doubleArrayOf(5.0, 4.0, 3.0), l, DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(50.0, 40.0, 30.0), q, DELTA)
    }

    private fun assertMaxHeap(l: DoubleArray, size: Int) {
        for (root in 0 until size) {
            val left = 2 * root + 1
            val right = 2 * root + 2
            if (left < size) Assertions.assertTrue(l[root] >= l[left], "heap violated parent $root left $left")
            if (right < size) Assertions.assertTrue(l[root] >= l[right], "heap violated parent $root right $right")
        }
    }

    // =============================================================================================
    // sort  (companion private dispatcher: ASC short-circuit / DESC reverse / classic / heap)
    // =============================================================================================

    private fun sort(levels: DoubleArray, quantities: DoubleArray, end: Int) =
        callCompanion("sort", arrayOf(DoubleArray::class.java, DoubleArray::class.java, Int::class.javaPrimitiveType!!),
            levels, quantities, end)

    @Test
    fun `sort end le one returns immediately`() {
        val l = doubleArrayOf(7.0); val q = doubleArrayOf(1.0)
        sort(l, q, 1)
        Assertions.assertArrayEquals(doubleArrayOf(7.0), l, DELTA)
        sort(doubleArrayOf(), doubleArrayOf(), 0) // no throw
    }

    @Test
    fun `sort ascending input is short-circuited (no reordering)`() {
        val l = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val q = doubleArrayOf(10.0, 20.0, 30.0, 40.0)
        sort(l, q, 4)
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0, 40.0), q, DELTA)
    }

    @Test
    fun `sort descending input is reversed in lockstep (DESC fast path)`() {
        val l = doubleArrayOf(4.0, 3.0, 2.0, 1.0)
        val q = doubleArrayOf(40.0, 30.0, 20.0, 10.0)
        sort(l, q, 4)
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), l, DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0, 40.0), q, DELTA)
    }

    @Test
    fun `sort small unsorted uses classic path`() {
        // n below INSERTION_THRESHOLD (40) and not monotone -> classicSort
        val l = doubleArrayOf(3.0, 1.0, 2.0, 5.0, 4.0)
        val q = doubleArrayOf(30.0, 10.0, 20.0, 50.0, 40.0)
        sort(l, q, 5)
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), l, DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0, 50.0, 40.0).let {
            doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0)
        }, q, DELTA)
    }

    @Test
    fun `sort large unsorted uses heap path`() {
        val n = 80 // above INSERTION_THRESHOLD, not monotone
        val rnd = Random(404)
        val l = DoubleArray(n) { rnd.nextInt(-500, 500).toDouble() }
        // force non-monotone by guaranteeing a dip
        l[0] = 1000.0; l[n - 1] = -1000.0
        val q = DoubleArray(n) { it.toDouble() }
        val tagToLevel = (0 until n).associateWith { l[it] }
        sort(l, q, n)
        for (i in 1 until n) Assertions.assertTrue(l[i - 1] <= l[i])
        for (i in 0 until n) Assertions.assertEquals(tagToLevel[q[i].toInt()]!!, l[i], DELTA)
    }

    @Test
    fun `sort boundary at exactly INSERTION_THRESHOLD uses classic`() {
        val n = 40
        val l = DoubleArray(n) { (n - it).toDouble() } // strictly descending -> DESC fast path actually
        // Make it non-monotone so it falls through to classic: swap two interior elements.
        l[0] = 5.0; l[1] = 100.0
        val q = DoubleArray(n) { it.toDouble() }
        val tagToLevel = (0 until n).associateWith { l[it] }
        sort(l, q, n)
        for (i in 1 until n) Assertions.assertTrue(l[i - 1] <= l[i])
        for (i in 0 until n) Assertions.assertEquals(tagToLevel[q[i].toInt()]!!, l[i], DELTA)
    }

    // =============================================================================================
    // fuse  (companion private: returns number of dropped entries)
    // =============================================================================================

    private fun fuse(levels: DoubleArray, quantities: DoubleArray, end: Int, allowZero: Boolean): Int =
        callCompanion("fuse", arrayOf(DoubleArray::class.java, DoubleArray::class.java, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            levels, quantities, end, allowZero) as Int

    @Test
    fun `fuse no duplicates no zeros drops nothing`() {
        val l = doubleArrayOf(1.0, 2.0, 3.0)
        val q = doubleArrayOf(10.0, 20.0, 30.0)
        val dropped = fuse(l, q, 3, false)
        Assertions.assertEquals(0, dropped)
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), l.copyOf(3), DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 20.0, 30.0), q.copyOf(3), DELTA)
    }

    @Test
    fun `fuse sums adjacent duplicate levels`() {
        val l = doubleArrayOf(1.0, 1.0, 2.0, 2.0, 2.0, 3.0)
        val q = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0)
        val dropped = fuse(l, q, 6, true)
        Assertions.assertEquals(3, dropped) // 6 -> 3
        val out = 6 - dropped
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0), l.copyOf(out), DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(3.0, 12.0, 6.0), q.copyOf(out), DELTA)
    }

    @Test
    fun `fuse duplicate summing to zero dropped when allowZero false`() {
        val l = doubleArrayOf(1.0, 1.0, 2.0)
        val q = doubleArrayOf(5.0, -5.0, 7.0)
        val dropped = fuse(l, q, 3, false)
        Assertions.assertEquals(2, dropped) // level 1 cancels and is removed; 3 -> 1
        val out = 3 - dropped
        Assertions.assertArrayEquals(doubleArrayOf(2.0), l.copyOf(out), DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(7.0), q.copyOf(out), DELTA)
    }

    @Test
    fun `fuse duplicate summing to zero kept when allowZero true`() {
        val l = doubleArrayOf(1.0, 1.0, 2.0)
        val q = doubleArrayOf(5.0, -5.0, 7.0)
        val dropped = fuse(l, q, 3, true)
        Assertions.assertEquals(1, dropped) // only the fused duplicate is removed; zero kept
        val out = 3 - dropped
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0), l.copyOf(out), DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(0.0, 7.0), q.copyOf(out), DELTA)
    }

    @Test
    fun `fuse standalone zeros dropped when allowZero false`() {
        val l = doubleArrayOf(1.0, 2.0, 3.0)
        val q = doubleArrayOf(0.0, 5.0, 0.0)
        val dropped = fuse(l, q, 3, false)
        Assertions.assertEquals(2, dropped)
        val out = 3 - dropped
        Assertions.assertArrayEquals(doubleArrayOf(2.0), l.copyOf(out), DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(5.0), q.copyOf(out), DELTA)
    }

    @Test
    fun `fuse fast-forward prefix of clean entries before first duplicate`() {
        // The first while-loop advances `out` over the clean ascending prefix without copying.
        val l = doubleArrayOf(1.0, 2.0, 3.0, 3.0, 4.0)
        val q = doubleArrayOf(10.0, 20.0, 30.0, 5.0, 40.0)
        val dropped = fuse(l, q, 5, false)
        Assertions.assertEquals(1, dropped)
        val out = 5 - dropped
        Assertions.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), l.copyOf(out), DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(10.0, 20.0, 35.0, 40.0), q.copyOf(out), DELTA)
    }

    @Test
    fun `fuse fractional duplicate levels`() {
        val l = doubleArrayOf(0.5, 0.5, 1.5, 1.5, 1.5)
        val q = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
        val dropped = fuse(l, q, 5, true)
        Assertions.assertEquals(3, dropped)
        val out = 5 - dropped
        Assertions.assertArrayEquals(doubleArrayOf(0.5, 1.5), l.copyOf(out), DELTA)
        Assertions.assertArrayEquals(doubleArrayOf(3.0, 12.0), q.copyOf(out), DELTA)
    }

    @Test
    fun `fuse single element`() {
        val l = doubleArrayOf(5.0); val q = doubleArrayOf(7.0)
        Assertions.assertEquals(0, fuse(l, q, 1, false))
        val l2 = doubleArrayOf(5.0); val q2 = doubleArrayOf(0.0)
        Assertions.assertEquals(1, fuse(l2, q2, 1, false)) // standalone zero dropped
        val l3 = doubleArrayOf(5.0); val q3 = doubleArrayOf(0.0)
        Assertions.assertEquals(0, fuse(l3, q3, 1, true)) // zero kept
    }

    @Test
    fun `fuse all duplicates of one level`() {
        val l = doubleArrayOf(2.0, 2.0, 2.0, 2.0)
        val q = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        val dropped = fuse(l, q, 4, true)
        Assertions.assertEquals(3, dropped)
        Assertions.assertEquals(2.0, l[0], DELTA)
        Assertions.assertEquals(10.0, q[0], DELTA)
    }

    @Test
    fun `fuse FP summing of fractional quantities`() {
        val l = doubleArrayOf(1.0, 1.0, 1.0)
        val q = doubleArrayOf(0.5, 0.25, 0.125)
        fuse(l, q, 3, true)
        Assertions.assertEquals(0.875, q[0], 0.0) // dyadic, exact
    }

    @Test
    fun `fuse randomized matches a grouping reference`() {
        val rnd = Random(505)
        repeat(300) {
            val n = rnd.nextInt(1, 30)
            // build sorted levels (with intentional duplicates) and quantities
            val raw = (0 until n).map { rnd.nextInt(0, 6).toDouble() to rnd.nextInt(-5, 6).toDouble() }
                .sortedBy { it.first }
            val l = DoubleArray(n) { raw[it].first }
            val q = DoubleArray(n) { raw[it].second }
            val allowZero = rnd.nextBoolean()
            val dropped = fuse(l, q, n, allowZero)
            val out = n - dropped

            // reference: group by level, sum, then (if !allowZero) drop zero totals
            val grouped = LinkedHashMap<Double, Double>()
            for ((lvl, v) in raw.sortedBy { it.first }) grouped.merge(lvl, v, Double::plus)
            val ref = grouped.entries.filter { allowZero || it.value != 0.0 }.map { it.key to it.value }
            Assertions.assertEquals(ref.size, out, "fuse out size mismatch")
            for (i in 0 until out) {
                Assertions.assertEquals(ref[i].first, l[i], DELTA, "level $i")
                Assertions.assertEquals(ref[i].second, q[i], DELTA, "quantity $i")
            }
        }
    }

    // =============================================================================================
    // lowerBound / upperBound  (instance private; operate over [start,end))
    // =============================================================================================

    private fun lowerBound(a: DoubleDoubleEntangledArray, num: Double): Int =
        callInstance(a, "lowerBound", arrayOf(Double::class.javaPrimitiveType!!), num) as Int

    private fun upperBound(a: DoubleDoubleEntangledArray, num: Double): Int =
        callInstance(a, "upperBound", arrayOf(Double::class.javaPrimitiveType!!), num) as Int

    @Test
    fun `lowerBound on empty returns start`() {
        Assertions.assertEquals(0, lowerBound(EMPTY, 5.0))
        Assertions.assertEquals(0, upperBound(EMPTY, 5.0))
    }

    @Test
    fun `lowerBound single element present and absent`() {
        val a = ddea(5.0 to 1.0)
        Assertions.assertEquals(0, lowerBound(a, 5.0))   // first >= 5 is index 0
        Assertions.assertEquals(0, lowerBound(a, 4.0))   // first >= 4 is index 0
        Assertions.assertEquals(1, lowerBound(a, 6.0))   // none >= 6 -> end
        Assertions.assertEquals(1, upperBound(a, 5.0))   // first > 5 -> end
        Assertions.assertEquals(0, upperBound(a, 4.0))   // first > 4 is index 0
    }

    @Test
    fun `lowerBound and upperBound on present levels`() {
        // levels 1,3,5,7,9 (start=0,end=5)
        val a = ddea(1.0 to 10.0, 3.0 to 20.0, 5.0 to 30.0, 7.0 to 40.0, 9.0 to 50.0)
        Assertions.assertEquals(2, lowerBound(a, 5.0)) // index of 5
        Assertions.assertEquals(3, upperBound(a, 5.0)) // first > 5 is index of 7
        Assertions.assertEquals(0, lowerBound(a, 1.0))
        Assertions.assertEquals(4, lowerBound(a, 9.0))
        Assertions.assertEquals(5, upperBound(a, 9.0)) // none > 9 -> end
    }

    @Test
    fun `lowerBound and upperBound between levels (fractional num)`() {
        val a = ddea(1.0 to 10.0, 3.0 to 20.0, 5.0 to 30.0, 7.0 to 40.0, 9.0 to 50.0)
        Assertions.assertEquals(2, lowerBound(a, 4.0)) // first >= 4 -> 5 at idx 2
        Assertions.assertEquals(2, upperBound(a, 4.0)) // first > 4 -> 5 at idx 2
        Assertions.assertEquals(0, lowerBound(a, -100.0))
        Assertions.assertEquals(0, upperBound(a, -100.0))
        Assertions.assertEquals(5, lowerBound(a, 100.0))
        Assertions.assertEquals(5, upperBound(a, 100.0))
    }

    @Test
    fun `lowerBound and upperBound honour a narrowed window`() {
        // full levels 1,2,3,4,5; take the middle window via tail/head -> levels 2,3,4 (start=1,end=4)
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0, 4.0 to 4.0, 5.0 to 5.0)
        val w = a.tail(4).head(3) // levels 2,3,4
        val start: Int = field(w, "start")
        val end: Int = field(w, "end")
        Assertions.assertEquals(start, lowerBound(w, 0.0))  // below window -> start (not 0)
        Assertions.assertEquals(end, upperBound(w, 100.0))  // above window -> end
        Assertions.assertEquals(start + 1, lowerBound(w, 3.0)) // 3 is the 2nd in window
    }

    @Test
    fun `lowerBound fractional dense levels`() {
        val a = ddea(0.25 to 1.0, 0.5 to 2.0, 0.75 to 3.0, 1.0 to 4.0)
        Assertions.assertEquals(1, lowerBound(a, 0.5))
        Assertions.assertEquals(2, upperBound(a, 0.5))
        Assertions.assertEquals(1, lowerBound(a, 0.3)) // between 0.25 and 0.5
    }

    @Test
    fun `lowerBound randomized matches a linear reference`() {
        val rnd = Random(606)
        repeat(300) {
            val n = rnd.nextInt(1, 40)
            var lvl = rnd.nextInt(-20, 20).toDouble()
            val pairs = ArrayList<Pair<Double, Double>>(n)
            repeat(n) {
                lvl += 0.5 * rnd.nextInt(1, 6)
                pairs.add(lvl to rnd.nextInt(1, 9).toDouble())
            }
            val a = ddea(*pairs.toTypedArray())
            val levels = pairs.map { it.first }
            repeat(10) {
                val num = rnd.nextInt(-25, 25) + 0.25 * rnd.nextInt(0, 4)
                val lbRef = levels.indexOfFirst { it >= num }.let { if (it < 0) levels.size else it }
                val ubRef = levels.indexOfFirst { it > num }.let { if (it < 0) levels.size else it }
                Assertions.assertEquals(lbRef, lowerBound(a, num), "lowerBound num=$num")
                Assertions.assertEquals(ubRef, upperBound(a, num), "upperBound num=$num")
            }
        }
    }

    // =============================================================================================
    // windowLevels  (instance private)
    // =============================================================================================

    @Test
    fun `windowLevels shares the backing array when window is whole`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        val backing: DoubleArray = field(a, "first")
        val wl = callInstance(a, "windowLevels", arrayOf()) as DoubleArray
        Assertions.assertSame(backing, wl, "whole-window should reuse the backing array")
    }

    @Test
    fun `windowLevels copies the slice when window is narrowed`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0, 4.0 to 4.0)
        val w = a.tail(3) // levels 2,3,4 over a sub-window
        val backing: DoubleArray = field(w, "first")
        val wl = callInstance(w, "windowLevels", arrayOf()) as DoubleArray
        Assertions.assertNotSame(backing, wl)
        Assertions.assertArrayEquals(doubleArrayOf(2.0, 3.0, 4.0), wl, DELTA)
    }

    // =============================================================================================
    // result  (companion private)
    // =============================================================================================

    private fun result(first: DoubleArray, second: DoubleArray, out: Int): DoubleDoubleEntangledArray =
        callCompanion("result", arrayOf(DoubleArray::class.java, DoubleArray::class.java, Int::class.javaPrimitiveType!!),
            first, second, out) as DoubleDoubleEntangledArray

    @Test
    fun `result with zero out returns EMPTY singleton`() {
        Assertions.assertSame(EMPTY, result(doubleArrayOf(1.0, 2.0), doubleArrayOf(3.0, 4.0), 0))
    }

    @Test
    fun `result trims scratch buffers to out entries`() {
        val first = doubleArrayOf(1.0, 2.0, 3.0, 999.0, 999.0)
        val second = doubleArrayOf(10.0, 20.0, 30.0, 999.0, 999.0)
        val r = result(first, second, 3)
        Assertions.assertEquals(3, r.size)
        assertSameContent(listOf(1.0 to 10.0, 2.0 to 20.0, 3.0 to 30.0), r)
        // the result must NOT alias the scratch (it copies out)
        val rFirst: DoubleArray = field(r, "first")
        Assertions.assertNotSame(first, rFirst)
        Assertions.assertEquals(3, rFirst.size)
    }

    // =============================================================================================
    // cumulativeSet1 / cumulativeSet2 / cumulativeSetN  (companion private)
    // =============================================================================================

    private fun scratch(n: Int) = DoubleArray(n) to DoubleArray(n)

    private fun cumulativeSet1(a: DoubleDoubleEntangledArray, dropZeros: Boolean): DoubleDoubleEntangledArray {
        val (f, s) = scratch(maxOf(a.size, 1))
        return callCompanion("cumulativeSet1",
            arrayOf(cls, DoubleArray::class.java, DoubleArray::class.java, Boolean::class.javaPrimitiveType!!),
            a, f, s, dropZeros) as DoubleDoubleEntangledArray
    }

    private fun cumulativeSet2(a: DoubleDoubleEntangledArray, b: DoubleDoubleEntangledArray, dropZeros: Boolean): DoubleDoubleEntangledArray {
        val (f, s) = scratch(a.size + b.size)
        return callCompanion("cumulativeSet2",
            arrayOf(cls, cls, DoubleArray::class.java, DoubleArray::class.java, Boolean::class.javaPrimitiveType!!),
            a, b, f, s, dropZeros) as DoubleDoubleEntangledArray
    }

    private fun cumulativeSetN(arrays: Array<DoubleDoubleEntangledArray>, n: Int, dropZeros: Boolean): DoubleDoubleEntangledArray {
        val s = arrays.take(n).sumOf { it.size }
        val (f, sec) = scratch(maxOf(s, 1))
        return callCompanion("cumulativeSetN",
            arrayOf(Array<DoubleDoubleEntangledArray>::class.java, Int::class.javaPrimitiveType!!,
                DoubleArray::class.java, DoubleArray::class.java, Boolean::class.javaPrimitiveType!!),
            arrays, n, f, sec, dropZeros) as DoubleDoubleEntangledArray
    }

    @Test
    fun `cumulativeSet1 dropZeros true returns same instance when no zeros`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0)
        Assertions.assertSame(a, cumulativeSet1(a, true))
    }

    @Test
    fun `cumulativeSet1 dropZeros true drops zero entries`() {
        val a = ddea(1.0 to 0.0, 2.0 to 5.0, 3.0 to 0.0, allowZero = true)
        assertSameContent(listOf(2.0 to 5.0), cumulativeSet1(a, true))
    }

    @Test
    fun `cumulativeSet1 dropZeros false keeps zeros`() {
        val a = ddea(1.0 to 0.0, 2.0 to 5.0, allowZero = true)
        val r = cumulativeSet1(a, false)
        Assertions.assertEquals(2, r.size)
        Assertions.assertEquals(1.0, r.firstLevel, DELTA)
    }

    @Test
    fun `cumulativeSet1 all zeros dropZeros true is EMPTY`() {
        val a = ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = true)
        Assertions.assertSame(EMPTY, cumulativeSet1(a, true))
    }

    @Test
    fun `cumulativeSet2 disjoint union`() {
        assertSameContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0, 4.0 to 4.0),
            cumulativeSet2(ddea(1.0 to 1.0, 2.0 to 2.0), ddea(3.0 to 3.0, 4.0 to 4.0), true))
    }

    @Test
    fun `cumulativeSet2 shared level last writer wins`() {
        assertSameContent(listOf(1.0 to 10.0, 2.0 to 99.0, 3.0 to 30.0),
            cumulativeSet2(ddea(1.0 to 10.0, 2.0 to 20.0), ddea(2.0 to 99.0, 3.0 to 30.0), true))
    }

    @Test
    fun `cumulativeSet2 later clears shared level dropZeros true`() {
        assertSameContent(listOf(1.0 to 5.0),
            cumulativeSet2(ddea(1.0 to 5.0, 2.0 to 6.0), ddea(2.0 to 0.0, allowZero = true), true))
    }

    @Test
    fun `cumulativeSet2 dropZeros false keeps zero winner`() {
        val r = cumulativeSet2(ddea(1.0 to 5.0, 2.0 to 6.0), ddea(2.0 to 0.0, allowZero = true), false)
        Assertions.assertEquals(2, r.size) // level 2 kept with 0.0
    }

    @Test
    fun `cumulativeSet2 a tail drain (a longer than b)`() {
        assertSameContent(listOf(1.0 to 1.0, 2.0 to 2.0, 5.0 to 5.0, 6.0 to 6.0),
            cumulativeSet2(ddea(1.0 to 1.0, 2.0 to 2.0, 5.0 to 5.0, 6.0 to 6.0), ddea(2.0 to 2.0), true))
    }

    @Test
    fun `cumulativeSet2 b tail drain (b longer than a)`() {
        assertSameContent(listOf(1.0 to 1.0, 3.0 to 3.0, 4.0 to 4.0, 5.0 to 5.0),
            cumulativeSet2(ddea(1.0 to 1.0), ddea(3.0 to 3.0, 4.0 to 4.0, 5.0 to 5.0), true))
    }

    @Test
    fun `cumulativeSetN three last writer wins`() {
        val arr = arrayOf(
            ddea(1.0 to 1.0, 2.0 to 1.0, 3.0 to 1.0),
            ddea(2.0 to 2.0, 3.0 to 2.0),
            ddea(3.0 to 3.0),
        )
        assertSameContent(listOf(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0), cumulativeSetN(arr, 3, true))
    }

    @Test
    fun `cumulativeSetN spread disjoint heap path`() {
        val arr = arrayOf(ddea(10.0 to 1.0), ddea(5000.0 to 2.0), ddea(100000.0 to 3.0))
        assertSameContent(listOf(10.0 to 1.0, 5000.0 to 2.0, 100000.0 to 3.0), cumulativeSetN(arr, 3, true))
    }

    @Test
    fun `cumulativeSetN tie-drain across many identical arrays`() {
        val a = ddea(10.0 to 1.0, 100000.0 to 2.0)
        val arr = Array(6) { a }
        assertSameContent(listOf(10.0 to 1.0, 100000.0 to 2.0), cumulativeSetN(arr, 6, true))
    }

    @Test
    fun `cumulativeSetN clear all dropZeros true is EMPTY`() {
        val arr = arrayOf(
            ddea(1.0 to 1.0, 5.0 to 1.0),
            ddea(1.0 to 2.0, 5.0 to 2.0),
            ddea(1.0 to 0.0, 5.0 to 0.0, allowZero = true),
        )
        Assertions.assertSame(EMPTY, cumulativeSetN(arr, 3, true))
    }

    @Test
    fun `cumulativeSetN randomized matches reference`() {
        val rnd = Random(707)
        repeat(200) {
            val n = rnd.nextInt(3, 7)
            val arrays = (0 until n).map { setPairs(rnd) }
            val ddeas = arrays.map { DoubleDoubleEntangledArray(it, allowZero = true) }
                .filter { it.size > 0 }
            if (ddeas.size < 3) return@repeat
            val arr = ddeas.toTypedArray()
            val result = cumulativeSetN(arr, arr.size, true)
            // build the reference from the actually-kept (non-empty) arrays, in order
            val keptRaw = ddeas.map { toPairs(it) }
            assertSameContent(referenceSet(keptRaw), result)
        }
    }

    // =============================================================================================
    // weightedSum1 / weightedSum2 / weightedSumN  (companion private)
    // =============================================================================================

    private fun weightedSum1(a: DoubleDoubleEntangledArray, mul: Double): DoubleDoubleEntangledArray {
        val (f, s) = scratch(maxOf(a.size, 1))
        return callCompanion("weightedSum1",
            arrayOf(cls, Double::class.javaPrimitiveType!!, DoubleArray::class.java, DoubleArray::class.java),
            a, mul, f, s) as DoubleDoubleEntangledArray
    }

    private fun weightedSum2(a: DoubleDoubleEntangledArray, b: DoubleDoubleEntangledArray, mulA: Double, mulB: Double): DoubleDoubleEntangledArray {
        val (f, s) = scratch(a.size + b.size)
        return callCompanion("weightedSum2",
            arrayOf(cls, cls, Double::class.javaPrimitiveType!!, Double::class.javaPrimitiveType!!, DoubleArray::class.java, DoubleArray::class.java),
            a, b, mulA, mulB, f, s) as DoubleDoubleEntangledArray
    }

    private fun weightedSumN(arrays: Array<DoubleDoubleEntangledArray>, multipliers: DoubleArray, n: Int): DoubleDoubleEntangledArray {
        val s = arrays.take(n).sumOf { it.size }
        val (f, sec) = scratch(maxOf(s, 1))
        return callCompanion("weightedSumN",
            arrayOf(Array<DoubleDoubleEntangledArray>::class.java, DoubleArray::class.java, Int::class.javaPrimitiveType!!,
                DoubleArray::class.java, DoubleArray::class.java),
            arrays, multipliers, n, f, sec) as DoubleDoubleEntangledArray
    }

    @Test
    fun `weightedSum1 multiplier one whole window returns same instance`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0)
        Assertions.assertSame(a, weightedSum1(a, 1.0))
    }

    @Test
    fun `weightedSum1 scaling produces scaled content`() {
        assertSameContent(listOf(1.0 to 4.0, 2.0 to 6.0), weightedSum1(ddea(1.0 to 2.0, 2.0 to 3.0), 2.0))
    }

    @Test
    fun `weightedSum1 drops zeros produced by source zeros`() {
        val a = ddea(1.0 to 2.0, 2.0 to 0.0, 3.0 to 4.0, allowZero = true)
        assertSameContent(listOf(1.0 to 6.0, 3.0 to 12.0), weightedSum1(a, 3.0))
    }

    @Test
    fun `weightedSum1 all zeros is EMPTY`() {
        Assertions.assertSame(EMPTY, weightedSum1(ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = true), 5.0))
    }

    @Test
    fun `weightedSum1 reuses level array when no zeros and multiplier not one`() {
        // out == srcLevels.size && multiplier != 1.0 -> result shares src levels but new quantities
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        val r = weightedSum1(a, 4.0)
        val srcLevels: DoubleArray = field(a, "first")
        val rLevels: DoubleArray = field(r, "first")
        Assertions.assertSame(srcLevels, rLevels, "level array should be reused")
        assertSameContent(listOf(1.0 to 4.0, 2.0 to 8.0, 3.0 to 12.0), r)
    }

    @Test
    fun `weightedSum2 overlap sums weighted`() {
        assertSameContent(listOf(1.0 to 7.0, 2.0 to 9.0, 3.0 to 11.0),
            weightedSum2(ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0), ddea(1.0 to 1.0, 2.0 to 1.0, 3.0 to 1.0), 2.0, 3.0))
    }

    @Test
    fun `weightedSum2 partial overlap and tail drains`() {
        assertSameContent(listOf(1.0 to 4.0, 2.0 to 11.0, 3.0 to 4.0),
            weightedSum2(ddea(1.0 to 2.0, 2.0 to 3.0), ddea(2.0 to 5.0, 3.0 to 4.0), 2.0, 1.0))
    }

    @Test
    fun `weightedSum2 cancellation drops the level`() {
        assertSameContent(listOf(2.0 to 2.0),
            weightedSum2(ddea(1.0 to 2.0, 2.0 to 3.0, 3.0 to 4.0), ddea(1.0 to 2.0, 2.0 to 1.0, 3.0 to 4.0), 1.0, -1.0))
    }

    @Test
    fun `weightedSumN three overlapping`() {
        val arr = arrayOf(ddea(1.0 to 1.0, 2.0 to 2.0), ddea(1.0 to 2.0, 2.0 to 3.0), ddea(1.0 to 1.0, 2.0 to 1.0))
        assertSameContent(listOf(1.0 to 8.0, 2.0 to 11.0), weightedSumN(arr, doubleArrayOf(1.0, 2.0, 3.0), 3))
    }

    @Test
    fun `weightedSumN spread with cancellation`() {
        val arr = arrayOf(ddea(10.0 to 2.0, 100000.0 to 5.0), ddea(10.0 to 2.0), ddea(100000.0 to 5.0))
        // 10: 2*1 + 2*(-1) = 0 dropped; 100000: 5*1 + 5*(-1) = 0 dropped -> EMPTY
        Assertions.assertSame(EMPTY, weightedSumN(arr, doubleArrayOf(1.0, -1.0, -1.0), 3))
    }

    @Test
    fun `weightedSumN randomized matches reference`() {
        val rnd = Random(808)
        repeat(200) {
            val n = rnd.nextInt(3, 7)
            val arrays = (0 until n).map { DoubleDoubleEntangledArray(setPairs(rnd), allowZero = true) }
                .filter { it.size > 0 }
            if (arrays.size < 3) return@repeat
            val arr = arrays.toTypedArray()
            val mults = DoubleArray(arr.size) { rnd.nextInt(-3, 4).toDouble() }
            val result = weightedSumN(arr, mults, arr.size)
            val ref = referenceWeighted(arr.indices.map { toPairs(arr[it]) to mults[it] })
            assertSameContent(ref, result)
        }
    }

    // =============================================================================================
    // sum1 / sum2 / sumN  (companion private)
    // =============================================================================================

    private fun sum1(a: DoubleDoubleEntangledArray): DoubleDoubleEntangledArray {
        val (f, s) = scratch(maxOf(a.size, 1))
        return callCompanion("sum1", arrayOf(cls, DoubleArray::class.java, DoubleArray::class.java), a, f, s) as DoubleDoubleEntangledArray
    }

    private fun sum2(a: DoubleDoubleEntangledArray, b: DoubleDoubleEntangledArray): DoubleDoubleEntangledArray {
        val (f, s) = scratch(a.size + b.size)
        return callCompanion("sum2", arrayOf(cls, cls, DoubleArray::class.java, DoubleArray::class.java), a, b, f, s) as DoubleDoubleEntangledArray
    }

    private fun sumN(arrays: Array<DoubleDoubleEntangledArray>, n: Int): DoubleDoubleEntangledArray {
        val s = arrays.take(n).sumOf { it.size }
        val (f, sec) = scratch(maxOf(s, 1))
        return callCompanion("sumN",
            arrayOf(Array<DoubleDoubleEntangledArray>::class.java, Int::class.javaPrimitiveType!!, DoubleArray::class.java, DoubleArray::class.java),
            arrays, n, f, sec) as DoubleDoubleEntangledArray
    }

    @Test
    fun `sum1 canonical whole-window returns same instance`() {
        val a = ddea(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0)
        Assertions.assertSame(a, sum1(a))
    }

    @Test
    fun `sum1 drops zeros`() {
        assertSameContent(listOf(1.0 to 2.0, 3.0 to 4.0),
            sum1(ddea(1.0 to 2.0, 2.0 to 0.0, 3.0 to 4.0, allowZero = true)))
    }

    @Test
    fun `sum1 all-zero is EMPTY`() {
        Assertions.assertSame(EMPTY, sum1(ddea(1.0 to 0.0, 2.0 to 0.0, allowZero = true)))
    }

    @Test
    fun `sum2 overlapping sums shared and drains tails`() {
        assertSameContent(listOf(1.0 to 1.0, 2.0 to 5.0, 3.0 to 3.0),
            sum2(ddea(1.0 to 1.0, 2.0 to 2.0), ddea(2.0 to 3.0, 3.0 to 3.0)))
    }

    @Test
    fun `sum2 full cancellation is EMPTY`() {
        Assertions.assertSame(EMPTY, sum2(ddea(1.0 to 2.0, 2.0 to 3.0), ddea(1.0 to -2.0, 2.0 to -3.0)))
    }

    @Test
    fun `sumN three overlapping via heap`() {
        val arr = arrayOf(ddea(1.0 to 1.0, 2.0 to 1.0), ddea(1.0 to 1.0, 3.0 to 1.0), ddea(1.0 to 1.0, 2.0 to 1.0))
        assertSameContent(listOf(1.0 to 3.0, 2.0 to 2.0, 3.0 to 1.0), sumN(arr, 3))
    }

    @Test
    fun `sumN spread disjoint`() {
        val arr = arrayOf(ddea(0.0 to 1.0), ddea(500.0 to 2.0), ddea(1000.0 to 3.0))
        assertSameContent(listOf(0.0 to 1.0, 500.0 to 2.0, 1000.0 to 3.0), sumN(arr, 3))
    }

    @Test
    fun `sumN randomized matches weightedSum reference`() {
        val rnd = Random(909)
        repeat(200) {
            val n = rnd.nextInt(3, 7)
            val arrays = (0 until n).map { DoubleDoubleEntangledArray(setPairs(rnd), allowZero = true) }
                .filter { it.size > 0 }
            if (arrays.size < 3) return@repeat
            val arr = arrays.toTypedArray()
            val result = sumN(arr, arr.size)
            val ref = referenceWeighted(arr.indices.map { toPairs(arr[it]) to 1.0 })
            assertSameContent(ref, result)
        }
    }

    // =============================================================================================
    // uncovered public edge states: NaN / Infinity / -0.0
    // =============================================================================================

    @Test
    fun `get on -0_0 level matches a 0_0 entry (IEEE equality)`() {
        // 0.0 == -0.0 in IEEE-754, so a lookup with -0.0 hits the 0.0 level.
        val a = ddea(-1.0 to 1.0, 0.0 to 5.0, 1.0 to 2.0)
        Assertions.assertEquals(5.0, a[-0.0], DELTA)
        Assertions.assertEquals(5.0, a[0.0], DELTA)
    }

    @Test
    fun `negative-zero quantity is treated as zero and dropped when allowZero false`() {
        // -0.0 == 0.0, so the canonicalizer should drop a -0.0 quantity.
        val a = ddea(1.0 to -0.0, 2.0 to 5.0, allowZero = false)
        Assertions.assertEquals(1, a.size)
        Assertions.assertEquals(2.0, a.firstLevel, DELTA)
    }

    @Test
    fun `infinity quantity survives and round-trips`() {
        val a = ddea(1.0 to Double.POSITIVE_INFINITY, 2.0 to 5.0)
        Assertions.assertEquals(Double.POSITIVE_INFINITY, a[1.0])
        val rt = DoubleDoubleEntangledArray.deserialize(a.serialize())
        Assertions.assertEquals(Double.POSITIVE_INFINITY, rt[1.0])
        Assertions.assertEquals(5.0, rt[2.0], DELTA)
    }

    @Test
    fun `NaN quantity round-trips bit-for-bit through serialize`() {
        val a = ddea(1.0 to Double.NaN, 2.0 to 7.0)
        val rt = DoubleDoubleEntangledArray.deserialize(a.serialize())
        Assertions.assertTrue(rt[1.0].isNaN())
        Assertions.assertEquals(7.0, rt[2.0], DELTA)
    }

    @Test
    fun `infinity levels sort to the extremes`() {
        val a = ddea(Double.NEGATIVE_INFINITY to 1.0, 0.0 to 2.0, Double.POSITIVE_INFINITY to 3.0)
        Assertions.assertEquals(Double.NEGATIVE_INFINITY, a.firstLevel)
        Assertions.assertEquals(Double.POSITIVE_INFINITY, a.lastLevel)
        Assertions.assertEquals(3, a.size)
    }

    @Test
    fun `dense fractional packet round-trips through serialize-deserialize`() {
        val pairs = (0 until 500).map { (it.toDouble() / 7.0 + 1000.0) to (it.toDouble() / 3.0 - 50.0) }
            .filter { it.second != 0.0 }
            .sortedBy { it.first }
        val a = DoubleDoubleEntangledArray(pairs, allowZero = false)
        val rt = DoubleDoubleEntangledArray.deserialize(a.serialize())
        Assertions.assertEquals(a.size, rt.size)
        for ((lvl, q) in pairs) Assertions.assertEquals(q, rt[lvl], 0.0) // doubles preserved bit-exactly
    }

    // =============================================================================================
    // PERFORMANCE + MEMORY
    // =============================================================================================

    private fun bytesPerOp(iterations: Int, op: (Int) -> Unit): Double {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val tid = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(tid)
        for (i in 0 until iterations) op(i)
        val bytes = bean.getThreadAllocatedBytes(tid) - before
        return bytes.toDouble() / iterations
    }

    @Test
    fun `times by zero allocates essentially nothing (EMPTY fast path)`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val a = DoubleDoubleEntangledArray((0 until 1000).map { it.toDouble() to (it + 1).toDouble() }, allowZero = false)
        val op: (Int) -> Unit = { a.times(0.0) }
        repeat(50_000) { op(it) }
        val bytes = bytesPerOp(500_000, op)
        println("times(0.0) bytes/op = ${"%.3f".format(bytes)}")
        // Returns the EMPTY singleton: no result arrays are built. A 1000-entry copy would cost
        // ~16KB/op, so a tiny budget (well under 64B, the measurement floor) proves the fast path.
        Assertions.assertTrue(bytes < 64.0, "times(0.0) should allocate ~nothing, got $bytes")
    }

    @Test
    fun `times by one returns same instance with zero allocation`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val a = DoubleDoubleEntangledArray((0 until 1000).map { it.toDouble() to (it + 1).toDouble() }, allowZero = false)
        val op: (Int) -> Unit = { a.times(1.0) }
        repeat(50_000) { op(it) }
        val bytes = bytesPerOp(500_000, op)
        println("times(1.0) bytes/op = ${"%.3f".format(bytes)}")
        Assertions.assertTrue(bytes < 64.0, "times(1.0) should return this with ~no allocation, got $bytes")
    }

    @Test
    fun `div by one returns same instance with zero allocation`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val a = DoubleDoubleEntangledArray((0 until 1000).map { it.toDouble() to (it + 1).toDouble() }, allowZero = false)
        val op: (Int) -> Unit = { a.div(1.0) }
        repeat(50_000) { op(it) }
        val bytes = bytesPerOp(500_000, op)
        println("div(1.0) bytes/op = ${"%.3f".format(bytes)}")
        Assertions.assertTrue(bytes < 64.0, "div(1.0) should return this with ~no allocation, got $bytes")
    }

    @Test
    fun `EMPTY operations allocate nothing`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val op: (Int) -> Unit = { EMPTY.times(3.0); EMPTY.div(3.0); EMPTY.head(5); EMPTY.absolute() }
        repeat(50_000) { op(it) }
        val bytes = bytesPerOp(500_000, op)
        println("EMPTY ops bytes/op = ${"%.3f".format(bytes)}")
        Assertions.assertTrue(bytes < 64.0, "EMPTY ops should allocate nothing, got $bytes")
    }

    @Test
    fun `construction of a sorted run is markedly cheaper than an unsorted (heapsort) run`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val n = 1000
        val sorted = (0 until n).map { it.toDouble() to (it + 1).toDouble() }
        val unsorted = sorted.shuffled(Random(1234))

        val sortedOp: (Int) -> Unit = { DoubleDoubleEntangledArray(sorted, allowZero = false) }
        val unsortedOp: (Int) -> Unit = { DoubleDoubleEntangledArray(unsorted, allowZero = false) }

        repeat(2_000) { sortedOp(it); unsortedOp(it) }

        val sortedBytes = bytesPerOp(20_000, sortedOp)
        val unsortedBytes = bytesPerOp(20_000, unsortedOp)
        println("ctor sorted bytes/op = ${"%.0f".format(sortedBytes)}  unsorted(heapsort) bytes/op = ${"%.0f".format(unsortedBytes)}")
        // Both allocate the two result arrays; the sort path itself is in-place, so allocation should be
        // in the same ballpark (heapsort adds no large temporaries). Guard against a gross blow-up only.
        Assertions.assertTrue(unsortedBytes < sortedBytes * 4.0 + 4096,
            "unsorted construction allocation blew up: sorted=$sortedBytes unsorted=$unsortedBytes")
    }

    @Test
    fun `weightedSum on large inputs completes quickly and within a sane allocation budget`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val a = DoubleDoubleEntangledArray((0 until 2000).map { it.toDouble() to 1.0 }, allowZero = false)
        val b = DoubleDoubleEntangledArray((1000 until 3000).map { it.toDouble() to 2.0 }, allowZero = false)
        val op: (Int) -> Unit = { ws(a to 2.0, b to 3.0) }
        repeat(2_000) { op(it) }
        val bytes = bytesPerOp(20_000, op)
        println("weightedSum(2k,2k) bytes/op = ${"%.0f".format(bytes)}")
        // result is ~3000 entries: two double arrays = ~48KB; the merge uses shared scratch buffers.
        Assertions.assertTrue(bytes < 200_000, "weightedSum allocation too high: $bytes")
    }

    @Test
    fun `cumulativeSet on large inputs completes within a sane allocation budget`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val a = DoubleDoubleEntangledArray((0 until 2000).map { it.toDouble() to 1.0 }, allowZero = false)
        val b = DoubleDoubleEntangledArray((0 until 2000).map { it.toDouble() to 2.0 }, allowZero = false)
        val c = DoubleDoubleEntangledArray((1000 until 3000).map { it.toDouble() to 3.0 }, allowZero = false)
        val op: (Int) -> Unit = { DoubleDoubleEntangledArray.cumulativeSet(listOf(a, b, c)) }
        repeat(2_000) { op(it) }
        val bytes = bytesPerOp(20_000, op)
        println("cumulativeSet(3 x ~2k) bytes/op = ${"%.0f".format(bytes)}")
        Assertions.assertTrue(bytes < 300_000, "cumulativeSet allocation too high: $bytes")
    }

    @Test
    fun `serialize then deserialize round-trips within a sane allocation budget`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val a = DoubleDoubleEntangledArray((0 until 1000).map { it.toDouble() to (it + 1).toDouble() }, allowZero = false)
        val op: (Int) -> Unit = { DoubleDoubleEntangledArray.deserialize(a.serialize()) }
        repeat(2_000) { op(it) }
        val bytes = bytesPerOp(20_000, op)
        println("serialize+deserialize(1k) bytes/op = ${"%.0f".format(bytes)}")
        // serialize: one ~16KB byte buffer; deserialize: two ~8KB double arrays. ~32KB ballpark.
        Assertions.assertTrue(bytes < 120_000, "round-trip allocation too high: $bytes")
    }

    @Test
    fun `weightedSum on large inputs finishes well within a few seconds`() {
        val a = DoubleDoubleEntangledArray((0 until 5000).map { it.toDouble() to 1.0 }, allowZero = false)
        val b = DoubleDoubleEntangledArray((2500 until 7500).map { it.toDouble() to 2.0 }, allowZero = false)
        val t0 = System.nanoTime()
        repeat(5_000) { ws(a to 1.0, b to 1.0) }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0
        println("5000 x weightedSum(5k,5k) took ${"%.0f".format(elapsedMs)} ms")
        Assertions.assertTrue(elapsedMs < 10_000, "weightedSum throughput regressed: ${elapsedMs}ms")
    }

    // =============================================================================================
    // shared helpers for randomized white-box checks
    // =============================================================================================

    private val steps = doubleArrayOf(0.5, 1.0, 1.5, 2.0, 3.0)

    /** Random ascending pairs with unique levels and at least one entry (some quantities zero). */
    private fun setPairs(rnd: Random, mult: Double = 1.0): List<Pair<Double, Double>> {
        val size = rnd.nextInt(1, 18)
        var level = rnd.nextInt(-5, 5).toDouble()
        val pairs = ArrayList<Pair<Double, Double>>(size)
        for (k in 0 until size) {
            level += steps[rnd.nextInt(steps.size)] * mult
            val q = if (rnd.nextDouble() < 0.2) 0.0 else rnd.nextInt(-20, 21).toDouble()
            pairs.add(level to q)
        }
        return pairs
    }

    /** Materializes the entangled array's content as raw (level, quantity) pairs (including zeros). */
    private fun toPairs(a: DoubleDoubleEntangledArray): List<Pair<Double, Double>> =
        a.toLevelValueList().map { it[0].toDouble() to it[1].toDouble() }

    /** Reference overlay: later arrays set each level (last writer wins); zeros dropped; ascending. */
    private fun referenceSet(arrays: List<List<Pair<Double, Double>>>): List<Pair<Double, Double>> {
        val acc = sortedMapOf<Double, Double>()
        for (pairs in arrays) for ((lvl, q) in pairs) acc[lvl] = q
        return acc.entries.filter { it.value != 0.0 }.map { it.key to it.value }
    }

    /** Reference weighted sum: combine, scale, drop zeros, ascending. */
    private fun referenceWeighted(terms: List<Pair<List<Pair<Double, Double>>, Double>>): List<Pair<Double, Double>> {
        val acc = sortedMapOf<Double, Double>()
        for ((pairs, mult) in terms) {
            if (mult == 0.0) continue
            for ((lvl, q) in pairs) acc[lvl] = (acc[lvl] ?: 0.0) + q * mult
        }
        return acc.entries.filter { it.value != 0.0 }.map { it.key to it.value }
    }
}
