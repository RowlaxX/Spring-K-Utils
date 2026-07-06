package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.lang.reflect.Method
import kotlin.math.abs
import kotlin.random.Random

/**
 * White-box / reflection coverage for [IntIntEntangledArray] filling the gaps the black-box
 * [IntIntEntangledArrayTest] leaves: the private sort / fuse / bounds / dense / merge helpers, plus
 * allocation and timing micro-benchmarks for the public hot paths.
 *
 * The existing black-box suite owns the public-API behaviour; this file deliberately reaches into
 * the private companion/instance helpers via reflection so that every branch (insertion vs heapsort,
 * dense vs merge dispatch, NaN-marker scatter, k-way heap tie-drain, …) is exercised directly.
 */
class IntIntEntangledArrayCoverageTest {

    private val EMPTY = IntIntEntangledArray.EMPTY

    // ---------- construction / content helpers (mirror the black-box suite) ---------------

    private fun iiea(vararg pairs: Pair<Int, Int>, allowZero: Boolean = false) =
        IntIntEntangledArray(pairs.toList(), allowZero)

    private fun ws(vararg terms: Pair<IntIntEntangledArray, Int>) =
        IntIntEntangledArray.weightedSum(terms.toList())

    /** Materializes the canonical/raw `(level, quantity)` content of a result by reading its private window. */
    private fun content(a: IntIntEntangledArray): List<Pair<Int, Int>> {
        val cls = IntIntEntangledArray::class.java
        val first = cls.getDeclaredField("first").apply { isAccessible = true }.get(a) as IntArray
        val second = cls.getDeclaredField("second").apply { isAccessible = true }.get(a) as IntArray
        val start = cls.getDeclaredField("start").apply { isAccessible = true }.getInt(a)
        val end = cls.getDeclaredField("end").apply { isAccessible = true }.getInt(a)
        return (start until end).map { first[it] to second[it] }
    }

    private fun assertContent(expected: List<Pair<Int, Int>>, actual: IntIntEntangledArray) {
        Assertions.assertEquals(expected.sortedBy { it.first }, content(actual).sortedBy { it.first })
    }

    private fun assertEmpty(actual: IntIntEntangledArray) {
        Assertions.assertSame(EMPTY, actual)
        Assertions.assertEquals(0, actual.size)
    }

    // ---------- reflection plumbing -------------------------------------------------------

    private val companion = IntIntEntangledArray.Companion
    private val companionClass = IntIntEntangledArray.Companion::class.java
    private val instanceClass = IntIntEntangledArray::class.java

    private fun companionMethod(name: String, vararg params: Class<*>): Method =
        companionClass.getDeclaredMethod(name, *params).apply { isAccessible = true }

    private fun instanceMethod(name: String, vararg params: Class<*>): Method =
        instanceClass.getDeclaredMethod(name, *params).apply { isAccessible = true }

    private fun invokeCompanion(name: String, paramTypes: Array<Class<*>>, vararg args: Any?): Any? =
        companionMethod(name, *paramTypes).invoke(companion, *args)

    /** Builds an array directly from the private two-arg constructor (canonical, start=0 end=size). */
    private fun direct(levels: IntArray, quantities: IntArray): IntIntEntangledArray {
        val ctor = instanceClass.getDeclaredConstructor(IntArray::class.java, IntArray::class.java)
            .apply { isAccessible = true }
        return ctor.newInstance(levels, quantities)
    }

    private fun arrayOfIIEA(vararg a: IntIntEntangledArray): Array<IntIntEntangledArray> = arrayOf(*a)

    // =====================================================================================
    // classicSort  (insertion sort, in place)
    // =====================================================================================

    private fun classicSort(levels: IntArray, quantities: IntArray, end: Int) =
        invokeCompanion("classicSort",
            arrayOf(IntArray::class.java, IntArray::class.java, Int::class.javaPrimitiveType!!),
            levels, quantities, end)

    @Test fun `classicSort empty range no-op`() {
        val l = intArrayOf(); val q = intArrayOf()
        classicSort(l, q, 0)
        Assertions.assertArrayEquals(intArrayOf(), l)
    }

    @Test fun `classicSort single element`() {
        val l = intArrayOf(5); val q = intArrayOf(9)
        classicSort(l, q, 1)
        Assertions.assertArrayEquals(intArrayOf(5), l)
        Assertions.assertArrayEquals(intArrayOf(9), q)
    }

    @Test fun `classicSort already ascending unchanged`() {
        val l = intArrayOf(1, 2, 3); val q = intArrayOf(10, 20, 30)
        classicSort(l, q, 3)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3), l)
        Assertions.assertArrayEquals(intArrayOf(10, 20, 30), q)
    }

    @Test fun `classicSort reverse sorted`() {
        val l = intArrayOf(3, 2, 1); val q = intArrayOf(30, 20, 10)
        classicSort(l, q, 3)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3), l)
        Assertions.assertArrayEquals(intArrayOf(10, 20, 30), q)
    }

    @Test fun `classicSort random keeps quantities paired with levels`() {
        val l = intArrayOf(3, 1, 4, 1, 5, 9, 2, 6); val q = intArrayOf(30, 11, 40, 12, 50, 90, 20, 60)
        classicSort(l, q, l.size)
        // levels ascending, equal levels keep stable relative order (insertion sort is stable)
        Assertions.assertArrayEquals(intArrayOf(1, 1, 2, 3, 4, 5, 6, 9), l)
        Assertions.assertArrayEquals(intArrayOf(11, 12, 20, 30, 40, 50, 60, 90), q)
    }

    @Test fun `classicSort negative levels`() {
        val l = intArrayOf(-1, -5, 3, -2); val q = intArrayOf(1, 5, 3, 2)
        classicSort(l, q, 4)
        Assertions.assertArrayEquals(intArrayOf(-5, -2, -1, 3), l)
        Assertions.assertArrayEquals(intArrayOf(5, 2, 1, 3), q)
    }

    @Test fun `classicSort partial end ignores tail`() {
        val l = intArrayOf(3, 1, 2, 99); val q = intArrayOf(30, 10, 20, 990)
        classicSort(l, q, 3) // only first 3 sorted
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3, 99), l)
        Assertions.assertArrayEquals(intArrayOf(10, 20, 30, 990), q)
    }

    @Test fun `classicSort with duplicate levels stable`() {
        val l = intArrayOf(2, 1, 2, 1); val q = intArrayOf(100, 200, 300, 400)
        classicSort(l, q, 4)
        Assertions.assertArrayEquals(intArrayOf(1, 1, 2, 2), l)
        Assertions.assertArrayEquals(intArrayOf(200, 400, 100, 300), q)
    }

    // =====================================================================================
    // sort  (direction-detect + insertion vs heapsort dispatch)
    // =====================================================================================

    private fun sort(levels: IntArray, quantities: IntArray, end: Int) =
        invokeCompanion("sort",
            arrayOf(IntArray::class.java, IntArray::class.java, Int::class.javaPrimitiveType!!),
            levels, quantities, end)

    @Test fun `sort end le one returns immediately`() {
        val l = intArrayOf(7); val q = intArrayOf(1)
        sort(l, q, 1)
        Assertions.assertArrayEquals(intArrayOf(7), l)
        sort(intArrayOf(), intArrayOf(), 0) // no throw
    }

    @Test fun `sort detects already ascending and skips`() {
        val l = intArrayOf(1, 2, 3, 4); val q = intArrayOf(10, 20, 30, 40)
        sort(l, q, 4)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3, 4), l)
        Assertions.assertArrayEquals(intArrayOf(10, 20, 30, 40), q)
    }

    @Test fun `sort detects descending and reverses both arrays`() {
        val l = intArrayOf(9, 7, 5, 3); val q = intArrayOf(90, 70, 50, 30)
        sort(l, q, 4)
        Assertions.assertArrayEquals(intArrayOf(3, 5, 7, 9), l)
        Assertions.assertArrayEquals(intArrayOf(30, 50, 70, 90), q)
    }

    @Test fun `sort small unsorted uses insertion path`() {
        val l = intArrayOf(3, 1, 2); val q = intArrayOf(30, 10, 20)
        sort(l, q, 3)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3), l)
        Assertions.assertArrayEquals(intArrayOf(10, 20, 30), q)
    }

    @Test fun `sort at insertion threshold boundary 40 uses classic`() {
        val rnd = Random(7)
        val n = 40
        val l = IntArray(n) { rnd.nextInt(-1000, 1000) }
        val q = IntArray(n) { it }
        val expected = l.indices.sortedWith(compareBy({ l[it] })).map { l[it] to q[it] }
        sort(l, q, n)
        Assertions.assertEquals(expected.map { it.first }, l.toList())
    }

    @Test fun `sort above threshold uses heapsort path`() {
        val rnd = Random(8)
        val n = 200 // > INSERTION_THRESHOLD (40)
        val l = IntArray(n) { rnd.nextInt(-100000, 100000) }
        val q = IntArray(n) { it * 3 }
        // expected: stable sort by level (packed key uses index as tiebreak so it's stable)
        val order = l.indices.sortedWith(compareBy({ l[it] }, { it }))
        val expL = order.map { l[it] }
        val expQ = order.map { q[it] }
        sort(l, q, n)
        Assertions.assertEquals(expL, l.toList())
        Assertions.assertEquals(expQ, q.toList())
    }

    @Test fun `sort large with duplicates keeps quantities aligned`() {
        val rnd = Random(9)
        val n = 300
        val l = IntArray(n) { rnd.nextInt(0, 20) } // many duplicates, forces unsorted heapsort branch
        val q = IntArray(n) { rnd.nextInt(-50, 50) }
        // capture (level,quantity) multiset before sort
        val before = (0 until n).map { l[it] to q[it] }.sortedWith(compareBy({ it.first }, { it.second }))
        sort(l, q, n)
        // ascending
        for (i in 1 until n) Assertions.assertTrue(l[i - 1] <= l[i], "not ascending at $i")
        val after = (0 until n).map { l[it] to q[it] }.sortedWith(compareBy({ it.first }, { it.second }))
        Assertions.assertEquals(before, after)
    }

    @Test fun `sort partial end leaves tail untouched`() {
        val l = intArrayOf(3, 1, 2, 100, 50); val q = intArrayOf(3, 1, 2, 100, 50)
        sort(l, q, 3)
        Assertions.assertArrayEquals(intArrayOf(1, 2, 3, 100, 50), l)
    }

    @Test fun `sort extreme values via heapsort`() {
        val n = 100
        val l = IntArray(n) { if (it % 2 == 0) Int.MAX_VALUE - it else Int.MIN_VALUE + it }
        val q = IntArray(n) { it }
        val order = l.indices.sortedWith(compareBy({ l[it] }, { it }))
        val expL = order.map { l[it] }
        sort(l, q, n)
        Assertions.assertEquals(expL, l.toList())
    }

    @Test fun `sort flat array all equal`() {
        val l = IntArray(60) { 5 }; val q = IntArray(60) { it }
        sort(l, q, 60) // sortedDirection sees ASC (this[start] <= this[start+1]) -> early return
        Assertions.assertTrue(l.all { it == 5 })
        Assertions.assertEquals((0 until 60).toList(), q.toList())
    }

    // =====================================================================================
    // fuse  (duplicate merge + zero drop), returns number removed
    // =====================================================================================

    private fun fuse(levels: IntArray, quantities: IntArray, end: Int, allowZero: Boolean): Int =
        invokeCompanion("fuse",
            arrayOf(IntArray::class.java, IntArray::class.java, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            levels, quantities, end, allowZero) as Int

    @Test fun `fuse no duplicates no zeros removes nothing`() {
        val l = intArrayOf(1, 2, 3); val q = intArrayOf(10, 20, 30)
        Assertions.assertEquals(0, fuse(l, q, 3, true))
        Assertions.assertEquals(0, fuse(l, q, 3, false))
    }

    @Test fun `fuse merges adjacent duplicate levels`() {
        val l = intArrayOf(1, 1, 2); val q = intArrayOf(2, 3, 5)
        val removed = fuse(l, q, 3, true)
        Assertions.assertEquals(1, removed)
        Assertions.assertEquals(1, l[0]); Assertions.assertEquals(5, q[0])
        Assertions.assertEquals(2, l[1]); Assertions.assertEquals(5, q[1])
    }

    @Test fun `fuse triple duplicate sums all`() {
        val l = intArrayOf(4, 4, 4); val q = intArrayOf(1, 2, 3)
        val removed = fuse(l, q, 3, true)
        Assertions.assertEquals(2, removed)
        Assertions.assertEquals(4, l[0]); Assertions.assertEquals(6, q[0])
    }

    @Test fun `fuse duplicate summing to zero dropped when allowZero false`() {
        val l = intArrayOf(1, 1, 2); val q = intArrayOf(2, -2, 5)
        val removed = fuse(l, q, 3, false)
        Assertions.assertEquals(2, removed) // 1 fused away + zero dropped
        Assertions.assertEquals(2, l[0]); Assertions.assertEquals(5, q[0])
    }

    @Test fun `fuse duplicate summing to zero kept when allowZero true`() {
        val l = intArrayOf(1, 1, 2); val q = intArrayOf(2, -2, 5)
        val removed = fuse(l, q, 3, true)
        Assertions.assertEquals(1, removed)
        Assertions.assertEquals(1, l[0]); Assertions.assertEquals(0, q[0])
        Assertions.assertEquals(2, l[1]); Assertions.assertEquals(5, q[1])
    }

    @Test fun `fuse standalone zeros dropped when allowZero false`() {
        val l = intArrayOf(1, 2, 3); val q = intArrayOf(0, 5, 0)
        val removed = fuse(l, q, 3, false)
        Assertions.assertEquals(2, removed)
        Assertions.assertEquals(2, l[0]); Assertions.assertEquals(5, q[0])
    }

    @Test fun `fuse standalone zeros kept when allowZero true`() {
        val l = intArrayOf(1, 2, 3); val q = intArrayOf(0, 5, 0)
        val removed = fuse(l, q, 3, true)
        Assertions.assertEquals(0, removed)
    }

    @Test fun `fuse fast-forward prefix is clean then dup appears`() {
        // first two entries are clean (distinct, nonzero); a dup follows, exercising the prefix scan
        val l = intArrayOf(1, 2, 3, 3); val q = intArrayOf(1, 2, 3, 4)
        val removed = fuse(l, q, 4, false)
        Assertions.assertEquals(1, removed)
        Assertions.assertEquals(3, l[2]); Assertions.assertEquals(7, q[2])
    }

    @Test fun `fuse leading zero with allowZero false breaks prefix immediately`() {
        val l = intArrayOf(5, 6); val q = intArrayOf(0, 7)
        val removed = fuse(l, q, 2, false)
        Assertions.assertEquals(1, removed)
        Assertions.assertEquals(6, l[0]); Assertions.assertEquals(7, q[0])
    }

    @Test fun `fuse all zeros allowZero false empties`() {
        val l = intArrayOf(1, 2, 3); val q = intArrayOf(0, 0, 0)
        Assertions.assertEquals(3, fuse(l, q, 3, false))
    }

    @Test fun `fuse single element`() {
        Assertions.assertEquals(0, fuse(intArrayOf(7), intArrayOf(3), 1, false))
        Assertions.assertEquals(1, fuse(intArrayOf(7), intArrayOf(0), 1, false))
        Assertions.assertEquals(0, fuse(intArrayOf(7), intArrayOf(0), 1, true))
    }

    @Test fun `fuse empty range`() {
        Assertions.assertEquals(0, fuse(intArrayOf(), intArrayOf(), 0, false))
    }

    @Test fun `fuse mixed dup and zero allowZero false`() {
        val l = intArrayOf(1, 1, 2, 3, 3); val q = intArrayOf(3, -3, 0, 4, 1)
        // level1 -> 0 dropped, level2 -> 0 dropped, level3 -> 5 kept
        val removed = fuse(l, q, 5, false)
        Assertions.assertEquals(4, removed)
        Assertions.assertEquals(3, l[0]); Assertions.assertEquals(5, q[0])
    }

    // =====================================================================================
    // lowerBound / upperBound  (instance, binary search over the window)
    // =====================================================================================

    private fun lowerBound(a: IntIntEntangledArray, num: Int): Int =
        instanceMethod("lowerBound", Int::class.javaPrimitiveType!!).invoke(a, num) as Int

    private fun upperBound(a: IntIntEntangledArray, num: Int): Int =
        instanceMethod("upperBound", Int::class.javaPrimitiveType!!).invoke(a, num) as Int

    @Test fun `lowerBound on empty returns end`() {
        Assertions.assertEquals(0, lowerBound(EMPTY, 5))
    }

    @Test fun `upperBound on empty returns end`() {
        Assertions.assertEquals(0, upperBound(EMPTY, 5))
    }

    @Test fun `lowerBound single present and absent`() {
        val a = iiea(5 to 1)
        Assertions.assertEquals(0, lowerBound(a, 5))  // first >= 5 is index 0
        Assertions.assertEquals(0, lowerBound(a, 4))  // first >= 4 is index 0
        Assertions.assertEquals(1, lowerBound(a, 6))  // none >= 6 -> end
    }

    @Test fun `upperBound single present and absent`() {
        val a = iiea(5 to 1)
        Assertions.assertEquals(1, upperBound(a, 5))  // first > 5 -> end
        Assertions.assertEquals(0, upperBound(a, 4))  // first > 4 is index 0
        Assertions.assertEquals(1, upperBound(a, 6))  // none > 6 -> end
    }

    @Test fun `lowerBound on multi-element present`() {
        val a = iiea(1 to 1, 3 to 1, 5 to 1, 7 to 1)
        Assertions.assertEquals(0, lowerBound(a, 1))
        Assertions.assertEquals(1, lowerBound(a, 3))
        Assertions.assertEquals(3, lowerBound(a, 7))
    }

    @Test fun `lowerBound on multi-element missing falls between`() {
        val a = iiea(1 to 1, 3 to 1, 5 to 1, 7 to 1)
        Assertions.assertEquals(0, lowerBound(a, 0))  // before all
        Assertions.assertEquals(1, lowerBound(a, 2))  // between 1 and 3
        Assertions.assertEquals(2, lowerBound(a, 4))  // between 3 and 5
        Assertions.assertEquals(4, lowerBound(a, 8))  // after all -> end
    }

    @Test fun `upperBound on multi-element`() {
        val a = iiea(1 to 1, 3 to 1, 5 to 1, 7 to 1)
        Assertions.assertEquals(1, upperBound(a, 1))
        Assertions.assertEquals(2, upperBound(a, 3))
        Assertions.assertEquals(4, upperBound(a, 7))
        Assertions.assertEquals(0, upperBound(a, 0))  // before all
        Assertions.assertEquals(2, upperBound(a, 4))  // between 3 and 5
    }

    @Test fun `bounds respect the windowed start and end via tail head`() {
        val full = iiea(1 to 1, 2 to 1, 3 to 1, 4 to 1, 5 to 1)
        val window = full.tail(3).head(2) // levels 3,4  with start=2 end=4
        // lowerBound of 3 should return start index (2) not 0
        Assertions.assertEquals(2, lowerBound(window, 3))
        Assertions.assertEquals(4, lowerBound(window, 5)) // none >= 5 within window -> end (4)
        Assertions.assertEquals(2, upperBound(window, 2)) // first > 2 within window is start
        Assertions.assertEquals(4, upperBound(window, 4)) // none > 4 within window -> end
    }

    @Test fun `lowerBound equals upperBound when num absent`() {
        val a = iiea(10 to 1, 20 to 1, 30 to 1)
        Assertions.assertEquals(lowerBound(a, 15), upperBound(a, 15))
        Assertions.assertEquals(lowerBound(a, 25), upperBound(a, 25))
    }

    @Test fun `upperBound minus lowerBound is one when num present`() {
        val a = iiea(10 to 1, 20 to 1, 30 to 1)
        Assertions.assertEquals(1, upperBound(a, 20) - lowerBound(a, 20))
    }

    @Test fun `bounds with extreme values`() {
        val a = iiea(Int.MIN_VALUE to 1, 0 to 1, Int.MAX_VALUE to 1)
        Assertions.assertEquals(0, lowerBound(a, Int.MIN_VALUE))
        Assertions.assertEquals(3, upperBound(a, Int.MAX_VALUE))
        Assertions.assertEquals(2, lowerBound(a, Int.MAX_VALUE))
    }

    @Test fun `lowerBound randomized matches linear scan`() {
        val rnd = Random(101)
        repeat(300) {
            var lvl = rnd.nextInt(-50, 0)
            val pairs = ArrayList<Pair<Int, Int>>()
            repeat(rnd.nextInt(1, 30)) { lvl += rnd.nextInt(1, 5); pairs.add(lvl to 1) }
            val a = iiea(*pairs.toTypedArray())
            val levels = pairs.map { it.first }
            repeat(20) {
                val num = rnd.nextInt(-60, 200)
                val expLb = levels.indexOfFirst { it >= num }.let { if (it < 0) levels.size else it }
                val expUb = levels.indexOfFirst { it > num }.let { if (it < 0) levels.size else it }
                Assertions.assertEquals(expLb, lowerBound(a, num), "lb num=$num")
                Assertions.assertEquals(expUb, upperBound(a, num), "ub num=$num")
            }
        }
    }

    // =====================================================================================
    // windowLevels  (share-or-copy of the level window)
    // =====================================================================================

    private fun windowLevels(a: IntIntEntangledArray): IntArray =
        instanceMethod("windowLevels").invoke(a) as IntArray

    @Test fun `windowLevels shares backing array when window spans whole`() {
        val a = iiea(1 to 1, 2 to 2, 3 to 3)
        val firstField = instanceClass.getDeclaredField("first").apply { isAccessible = true }
        val backing = firstField.get(a) as IntArray
        Assertions.assertSame(backing, windowLevels(a)) // same instance, no copy
    }

    @Test fun `windowLevels copies the slice for a narrowed window`() {
        val a = iiea(1 to 1, 2 to 2, 3 to 3, 4 to 4)
        val w = a.tail(2) // levels 3,4 with start=2
        val firstField = instanceClass.getDeclaredField("first").apply { isAccessible = true }
        val backing = firstField.get(w) as IntArray
        val levels = windowLevels(w)
        Assertions.assertNotSame(backing, levels)
        Assertions.assertArrayEquals(intArrayOf(3, 4), levels)
    }

    @Test fun `windowLevels on EMPTY shares the empty backing`() {
        val levels = windowLevels(EMPTY)
        Assertions.assertEquals(0, levels.size)
    }

    @Test fun `windowLevels of an interior head window`() {
        val a = iiea(1 to 1, 2 to 1, 3 to 1, 4 to 1, 5 to 1)
        val w = a.head(4).tail(2) // levels 3,4
        Assertions.assertArrayEquals(intArrayOf(3, 4), windowLevels(w))
    }

    @Test fun `times uses windowLevels copy path on a window`() {
        val a = iiea(1 to 1, 2 to 2, 3 to 3, 4 to 4)
        val scaled = a.tail(2).times(2)
        assertContent(listOf(3 to 6, 4 to 8), scaled)
    }

    @Test fun `div uses windowLevels copy path on a window`() {
        val a = iiea(1 to 10, 2 to 20, 3 to 30, 4 to 40)
        val divided = a.tail(2).div(10)
        assertContent(listOf(3 to 3, 4 to 4), divided)
    }

    // =====================================================================================
    // result  (trim scratch -> EMPTY when empty, copyOf otherwise)
    // =====================================================================================

    private fun result(first: IntArray, second: IntArray, out: Int): IntIntEntangledArray =
        invokeCompanion("result",
            arrayOf(IntArray::class.java, IntArray::class.java, Int::class.javaPrimitiveType!!),
            first, second, out) as IntIntEntangledArray

    @Test fun `result with zero out is EMPTY singleton`() {
        Assertions.assertSame(EMPTY, result(intArrayOf(1, 2, 3), intArrayOf(4, 5, 6), 0))
    }

    @Test fun `result copies only the first out entries`() {
        val r = result(intArrayOf(1, 2, 9, 9), intArrayOf(10, 20, 99, 99), 2)
        Assertions.assertNotSame(EMPTY, r)
        assertContent(listOf(1 to 10, 2 to 20), r)
        Assertions.assertEquals(2, r.size)
    }

    @Test fun `result full length`() {
        val r = result(intArrayOf(1, 2, 3), intArrayOf(10, 20, 30), 3)
        assertContent(listOf(1 to 10, 2 to 20, 3 to 30), r)
    }

    @Test fun `result truncates the backing arrays via copyOf`() {
        val first = intArrayOf(1, 2, 3, 4, 5)
        val r = result(first, intArrayOf(1, 2, 3, 4, 5), 3)
        // mutating the original scratch must not affect the result (it was copied)
        first[0] = 999
        assertContent(listOf(1 to 1, 2 to 2, 3 to 3), r)
    }

    // =====================================================================================
    // collectDenseSet  (NaN-marker compaction)
    // =====================================================================================

    private fun collectDenseSet(
        first: IntArray, second: IntArray, scatter: DoubleArray,
        minLevel: Int, width: Int, dropZeros: Boolean,
    ): IntIntEntangledArray =
        invokeCompanion("collectDenseSet",
            arrayOf(IntArray::class.java, IntArray::class.java, DoubleArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            first, second, scatter, minLevel, width, dropZeros) as IntIntEntangledArray

    @Test fun `collectDenseSet skips NaN absent levels`() {
        val scatter = doubleArrayOf(Double.NaN, 5.0, Double.NaN, 7.0)
        val r = collectDenseSet(IntArray(4), IntArray(4), scatter, 10, 4, true)
        assertContent(listOf(11 to 5, 13 to 7), r)
    }

    @Test fun `collectDenseSet drops real zero when dropZeros true`() {
        val scatter = doubleArrayOf(5.0, 0.0, 7.0)
        val r = collectDenseSet(IntArray(3), IntArray(3), scatter, 0, 3, true)
        assertContent(listOf(0 to 5, 2 to 7), r)
    }

    @Test fun `collectDenseSet keeps real zero when dropZeros false`() {
        val scatter = doubleArrayOf(5.0, 0.0, 7.0)
        val r = collectDenseSet(IntArray(3), IntArray(3), scatter, 0, 3, false)
        assertContent(listOf(0 to 5, 1 to 0, 2 to 7), r)
    }

    @Test fun `collectDenseSet keeps zero distinct from NaN when dropZeros false`() {
        // NaN absent levels still skipped even when dropZeros=false
        val scatter = doubleArrayOf(Double.NaN, 0.0, Double.NaN)
        val r = collectDenseSet(IntArray(3), IntArray(3), scatter, 100, 3, false)
        assertContent(listOf(101 to 0), r)
    }

    @Test fun `collectDenseSet all NaN yields EMPTY`() {
        val scatter = doubleArrayOf(Double.NaN, Double.NaN)
        Assertions.assertSame(EMPTY, collectDenseSet(IntArray(2), IntArray(2), scatter, 0, 2, true))
    }

    @Test fun `collectDenseSet all-zero dropZeros true yields EMPTY`() {
        val scatter = doubleArrayOf(0.0, 0.0)
        Assertions.assertSame(EMPTY, collectDenseSet(IntArray(2), IntArray(2), scatter, 0, 2, true))
    }

    @Test fun `collectDenseSet negative levels and quantities`() {
        val scatter = doubleArrayOf(-3.0, Double.NaN, 4.0)
        val r = collectDenseSet(IntArray(3), IntArray(3), scatter, -5, 3, true)
        assertContent(listOf(-5 to -3, -3 to 4), r)
    }

    @Test fun `collectDenseSet level recovered as minLevel plus index`() {
        val scatter = doubleArrayOf(1.0, 2.0, 3.0)
        val r = collectDenseSet(IntArray(3), IntArray(3), scatter, 1000, 3, true)
        assertContent(listOf(1000 to 1, 1001 to 2, 1002 to 3), r)
    }

    // =====================================================================================
    // collectDenseSum  (zero-additive-identity compaction, reads from second[0..width))
    // =====================================================================================

    private fun collectDenseSum(first: IntArray, second: IntArray, minLevel: Int, width: Int): IntIntEntangledArray =
        invokeCompanion("collectDenseSum",
            arrayOf(IntArray::class.java, IntArray::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            first, second, minLevel, width) as IntIntEntangledArray

    @Test fun `collectDenseSum drops zeros and keeps non-zero`() {
        val second = intArrayOf(5, 0, 7, 0)
        val r = collectDenseSum(IntArray(4), second, 10, 4)
        assertContent(listOf(10 to 5, 12 to 7), r)
    }

    @Test fun `collectDenseSum all zero yields EMPTY`() {
        Assertions.assertSame(EMPTY, collectDenseSum(IntArray(3), intArrayOf(0, 0, 0), 0, 3))
    }

    @Test fun `collectDenseSum negative levels`() {
        val r = collectDenseSum(IntArray(3), intArrayOf(-1, 0, 2), -10, 3)
        assertContent(listOf(-10 to -1, -8 to 2), r)
    }

    @Test fun `collectDenseSum in-place compaction writes back into same arrays`() {
        // first==second is not the contract but width slicing is: only [0,width) consulted.
        val second = intArrayOf(0, 9, 0, 8, 1234)
        val r = collectDenseSum(IntArray(5), second, 0, 4) // width 4 ignores index 4
        assertContent(listOf(1 to 9, 3 to 8), r)
    }

    @Test fun `collectDenseSum single survivor`() {
        val r = collectDenseSum(IntArray(5), intArrayOf(0, 0, 42, 0, 0), 100, 5)
        assertContent(listOf(102 to 42), r)
    }

    // =====================================================================================
    // denseSet2  (two-array dense overlay via NaN scatter)
    // =====================================================================================

    private fun denseSet2(a: IntIntEntangledArray, b: IntIntEntangledArray, minLevel: Int, span: Int, dropZeros: Boolean): IntIntEntangledArray {
        val width = span + 1
        return invokeCompanion("denseSet2",
            arrayOf(IntIntEntangledArray::class.java, IntIntEntangledArray::class.java,
                IntArray::class.java, IntArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            a, b, IntArray(width), IntArray(width), minLevel, span, dropZeros) as IntIntEntangledArray
    }

    @Test fun `denseSet2 last writer wins on overlap`() {
        val a = iiea(0 to 1, 1 to 2, 2 to 3)
        val b = iiea(1 to 99)
        assertContent(listOf(0 to 1, 1 to 99, 2 to 3), denseSet2(a, b, 0, 2, true))
    }

    @Test fun `denseSet2 disjoint union`() {
        val a = iiea(0 to 1, 1 to 2)
        val b = iiea(2 to 3, 3 to 4)
        assertContent(listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4), denseSet2(a, b, 0, 3, true))
    }

    @Test fun `denseSet2 later clears to zero dropped`() {
        val a = iiea(0 to 5, 1 to 6, 2 to 7)
        val b = iiea(1 to 0, allowZero = true)
        assertContent(listOf(0 to 5, 2 to 7), denseSet2(a, b, 0, 2, true))
    }

    @Test fun `denseSet2 later clears to zero kept when dropZeros false`() {
        val a = iiea(0 to 5, 1 to 6, 2 to 7)
        val b = iiea(1 to 0, allowZero = true)
        assertContent(listOf(0 to 5, 1 to 0, 2 to 7), denseSet2(a, b, 0, 2, false))
    }

    @Test fun `denseSet2 absent level stays absent even with dropZeros false`() {
        val a = iiea(0 to 5, 2 to 7) // level 1 carried by no input
        val b = iiea(0 to 9)
        assertContent(listOf(0 to 9, 2 to 7), denseSet2(a, b, 0, 2, false))
    }

    // =====================================================================================
    // mergeSet2  (two-array sparse overlay via merge)
    // =====================================================================================

    private fun mergeSet2(a: IntIntEntangledArray, b: IntIntEntangledArray, dropZeros: Boolean): IntIntEntangledArray {
        val s = a.size + b.size
        return invokeCompanion("mergeSet2",
            arrayOf(IntIntEntangledArray::class.java, IntIntEntangledArray::class.java,
                IntArray::class.java, IntArray::class.java, Boolean::class.javaPrimitiveType!!),
            a, b, IntArray(s), IntArray(s), dropZeros) as IntIntEntangledArray
    }

    @Test fun `mergeSet2 spread last writer wins`() {
        val a = iiea(10 to 1, 100000 to 2)
        val b = iiea(10 to 9, 5000 to 3)
        assertContent(listOf(10 to 9, 5000 to 3, 100000 to 2), mergeSet2(a, b, true))
    }

    @Test fun `mergeSet2 disjoint tails both sides drained`() {
        val a = iiea(10 to 1, 20 to 2, 30 to 3)
        val b = iiea(5000 to 9)
        assertContent(listOf(10 to 1, 20 to 2, 30 to 3, 5000 to 9), mergeSet2(a, b, true))
    }

    @Test fun `mergeSet2 b drained first then a tail`() {
        val a = iiea(1 to 1, 2 to 2, 100000 to 3)
        val b = iiea(1 to 9)
        assertContent(listOf(1 to 9, 2 to 2, 100000 to 3), mergeSet2(a, b, true))
    }

    @Test fun `mergeSet2 shared cleared to zero dropped`() {
        val a = iiea(10 to 5, 100000 to 7)
        val b = iiea(100000 to 0, allowZero = true)
        assertContent(listOf(10 to 5), mergeSet2(a, b, true))
    }

    @Test fun `mergeSet2 zeros kept when dropZeros false`() {
        val a = iiea(10 to 5, 100000 to 7)
        val b = iiea(100000 to 0, allowZero = true)
        assertContent(listOf(10 to 5, 100000 to 0), mergeSet2(a, b, false))
    }

    // =====================================================================================
    // denseSetN / mergeSetN  (N-way overlay)
    // =====================================================================================

    private fun denseSetN(arrays: Array<IntIntEntangledArray>, n: Int, minLevel: Int, span: Int, dropZeros: Boolean): IntIntEntangledArray {
        val width = span + 1
        return invokeCompanion("denseSetN",
            arrayOf(Array<IntIntEntangledArray>::class.java, Int::class.javaPrimitiveType!!,
                IntArray::class.java, IntArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            arrays, n, IntArray(width), IntArray(width), minLevel, span, dropZeros) as IntIntEntangledArray
    }

    private fun mergeSetN(arrays: Array<IntIntEntangledArray>, n: Int, dropZeros: Boolean): IntIntEntangledArray {
        var s = 0; for (k in 0 until n) s += arrays[k].size
        return invokeCompanion("mergeSetN",
            arrayOf(Array<IntIntEntangledArray>::class.java, Int::class.javaPrimitiveType!!,
                IntArray::class.java, IntArray::class.java, Boolean::class.javaPrimitiveType!!),
            arrays, n, IntArray(s), IntArray(s), dropZeros) as IntIntEntangledArray
    }

    @Test fun `denseSetN last writer wins across three`() {
        val arr = arrayOfIIEA(iiea(0 to 1, 1 to 1, 2 to 1), iiea(1 to 2, 2 to 2), iiea(2 to 3))
        assertContent(listOf(0 to 1, 1 to 2, 2 to 3), denseSetN(arr, 3, 0, 2, true))
    }

    @Test fun `denseSetN clears to zero dropped`() {
        val arr = arrayOfIIEA(iiea(0 to 5, 1 to 6), iiea(0 to 0, allowZero = true))
        assertContent(listOf(1 to 6), denseSetN(arr, 2, 0, 1, true))
    }

    @Test fun `denseSetN holes preserved as absent`() {
        val arr = arrayOfIIEA(iiea(0 to 1, 3 to 1), iiea(3 to 9))
        assertContent(listOf(0 to 1, 3 to 9), denseSetN(arr, 2, 0, 3, false))
    }

    @Test fun `mergeSetN heap drains ties keeping last writer`() {
        val arr = arrayOfIIEA(
            iiea(10 to 1, 100000 to 1),
            iiea(10 to 2, 5000 to 2),
            iiea(100000 to 3))
        assertContent(listOf(10 to 2, 5000 to 2, 100000 to 3), mergeSetN(arr, 3, true))
    }

    @Test fun `mergeSetN all on one level last wins`() {
        val arr = arrayOfIIEA(iiea(5 to 1), iiea(5 to 2), iiea(5 to 3))
        assertContent(listOf(5 to 3), mergeSetN(arr, 3, true))
    }

    @Test fun `mergeSetN spread disjoint union`() {
        val arr = arrayOfIIEA(iiea(10 to 1), iiea(5000 to 2), iiea(100000 to 3))
        assertContent(listOf(10 to 1, 5000 to 2, 100000 to 3), mergeSetN(arr, 3, true))
    }

    @Test fun `mergeSetN clear all yields EMPTY`() {
        val arr = arrayOfIIEA(
            iiea(10 to 5, 100000 to 6),
            iiea(10 to 0, 100000 to 0, allowZero = true))
        Assertions.assertSame(EMPTY, mergeSetN(arr, 2, true))
    }

    @Test fun `mergeSetN heap with many inputs sifts correctly`() {
        // 6 spread arrays exercise the heapify + repeated siftDown
        val arr = arrayOfIIEA(
            iiea(60000 to 6), iiea(10 to 1), iiea(50000 to 5),
            iiea(20000 to 2), iiea(40000 to 4), iiea(30000 to 3))
        assertContent(
            listOf(10 to 1, 20000 to 2, 30000 to 3, 40000 to 4, 50000 to 5, 60000 to 6),
            mergeSetN(arr, 6, true))
    }

    @Test fun `mergeSetN uneven length arrays drain correctly`() {
        val arr = arrayOfIIEA(
            iiea(1 to 1, 100 to 1, 10000 to 1),
            iiea(100 to 2),
            iiea(10000 to 3, 1000000 to 3))
        assertContent(listOf(1 to 1, 100 to 2, 10000 to 3, 1000000 to 3), mergeSetN(arr, 3, true))
    }

    // =====================================================================================
    // weightedSum1  (single-array scale, reuse fast paths)
    // =====================================================================================

    private fun weightedSum1(array: IntIntEntangledArray, multiplier: Int): IntIntEntangledArray {
        val s = array.size
        return invokeCompanion("weightedSum1",
            arrayOf(IntIntEntangledArray::class.java, Int::class.javaPrimitiveType!!,
                IntArray::class.java, IntArray::class.java),
            array, multiplier, IntArray(s), IntArray(s)) as IntIntEntangledArray
    }

    @Test fun `weightedSum1 multiplier one full span returns same instance`() {
        val a = iiea(1 to 2, 2 to 3, 3 to 4)
        Assertions.assertSame(a, weightedSum1(a, 1))
    }

    @Test fun `weightedSum1 scales`() {
        assertContent(listOf(1 to 4, 2 to 6), weightedSum1(iiea(1 to 2, 2 to 3), 2))
    }

    @Test fun `weightedSum1 drops zero quantities`() {
        val a = iiea(1 to 2, 2 to 0, 3 to 4, allowZero = true)
        assertContent(listOf(1 to 6, 3 to 12), weightedSum1(a, 3))
    }

    @Test fun `weightedSum1 all zero is EMPTY`() {
        Assertions.assertSame(EMPTY, weightedSum1(iiea(1 to 0, 2 to 0, allowZero = true), 5))
    }

    @Test fun `weightedSum1 zero multiplier yields EMPTY`() {
        Assertions.assertSame(EMPTY, weightedSum1(iiea(1 to 2, 2 to 3), 0))
    }

    @Test fun `weightedSum1 negative multiplier`() {
        assertContent(listOf(1 to -4, 2 to -6), weightedSum1(iiea(1 to 2, 2 to 3), -2))
    }

    @Test fun `weightedSum1 full span multiplier not one reuses levels`() {
        // out == srcLevels.size && multiplier != 1 -> shares levels but new quantities
        val a = iiea(1 to 1, 2 to 2, 3 to 3)
        val r = weightedSum1(a, 2)
        assertContent(listOf(1 to 2, 2 to 4, 3 to 6), r)
        Assertions.assertNotSame(a, r)
    }

    // =====================================================================================
    // sum1  (single-array zero-drop)
    // =====================================================================================

    private fun sum1(array: IntIntEntangledArray): IntIntEntangledArray {
        val s = array.size
        return invokeCompanion("sum1",
            arrayOf(IntIntEntangledArray::class.java, IntArray::class.java, IntArray::class.java),
            array, IntArray(s), IntArray(s)) as IntIntEntangledArray
    }

    @Test fun `sum1 canonical full span returns same instance`() {
        val a = iiea(1 to 1, 2 to 2, 3 to 3)
        Assertions.assertSame(a, sum1(a))
    }

    @Test fun `sum1 drops zeros`() {
        assertContent(listOf(1 to 2, 3 to 4), sum1(iiea(1 to 2, 2 to 0, 3 to 4, allowZero = true)))
    }

    @Test fun `sum1 all zero is EMPTY`() {
        Assertions.assertSame(EMPTY, sum1(iiea(1 to 0, 2 to 0, allowZero = true)))
    }

    @Test fun `sum1 single element`() {
        val a = iiea(7 to 3)
        Assertions.assertSame(a, sum1(a))
    }

    // =====================================================================================
    // denseSum2 / mergeSum2  (weighted two-array)
    // =====================================================================================

    private fun denseSum2(a: IntIntEntangledArray, b: IntIntEntangledArray, mulA: Int, mulB: Int, minLevel: Int, span: Int): IntIntEntangledArray {
        val width = span + 1
        return invokeCompanion("denseSum2",
            arrayOf(IntIntEntangledArray::class.java, IntIntEntangledArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                IntArray::class.java, IntArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            a, b, mulA, mulB, IntArray(width), IntArray(width), minLevel, span) as IntIntEntangledArray
    }

    private fun mergeSum2(a: IntIntEntangledArray, b: IntIntEntangledArray, mulA: Int, mulB: Int): IntIntEntangledArray {
        val s = a.size + b.size
        return invokeCompanion("mergeSum2",
            arrayOf(IntIntEntangledArray::class.java, IntIntEntangledArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                IntArray::class.java, IntArray::class.java),
            a, b, mulA, mulB, IntArray(s), IntArray(s)) as IntIntEntangledArray
    }

    @Test fun `denseSum2 weighted overlap`() {
        assertContent(listOf(0 to 7, 1 to 9, 2 to 11),
            denseSum2(iiea(0 to 2, 1 to 3, 2 to 4), iiea(0 to 1, 1 to 1, 2 to 1), 2, 3, 0, 2))
    }

    @Test fun `denseSum2 cancellation drops level`() {
        assertContent(listOf(1 to 2),
            denseSum2(iiea(0 to 2, 1 to 3), iiea(0 to 2, 1 to 1), 1, -1, 0, 1))
    }

    @Test fun `denseSum2 disjoint with holes`() {
        assertContent(listOf(0 to 2, 3 to 5),
            denseSum2(iiea(0 to 1), iiea(3 to 5), 2, 1, 0, 3))
    }

    @Test fun `denseSum2 all cancel yields EMPTY`() {
        Assertions.assertSame(EMPTY, denseSum2(iiea(0 to 5, 1 to 6), iiea(0 to 5, 1 to 6), 1, -1, 0, 1))
    }

    @Test fun `mergeSum2 spread overlap sums shared`() {
        assertContent(listOf(10 to 5, 5000 to 6, 999999 to 4),
            mergeSum2(iiea(10 to 2, 5000 to 3), iiea(10 to 1, 999999 to 4), 2, 1))
    }

    @Test fun `mergeSum2 disjoint tails`() {
        assertContent(listOf(10 to 2, 1000 to 6, 100000 to 4),
            mergeSum2(iiea(10 to 1, 1000 to 3), iiea(100000 to 4), 2, 1))
    }

    @Test fun `mergeSum2 cancellation drops shared`() {
        assertContent(listOf(5000 to 3, 999999 to -4),
            mergeSum2(iiea(10 to 2, 5000 to 3), iiea(10 to 2, 999999 to 4), 1, -1))
    }

    @Test fun `mergeSum2 a tail drained`() {
        assertContent(listOf(1 to 1, 2 to 2, 100000 to 3),
            mergeSum2(iiea(1 to 1, 2 to 2, 100000 to 3), iiea(1 to 0, allowZero = true), 1, 1))
    }

    // =====================================================================================
    // denseSumN / mergeSumN  (weighted N-array)
    // =====================================================================================

    private fun denseSumN(arrays: Array<IntIntEntangledArray>, multipliers: IntArray, minLevel: Int, span: Int): IntIntEntangledArray {
        val width = span + 1
        return invokeCompanion("denseSumN",
            arrayOf(Array<IntIntEntangledArray>::class.java, IntArray::class.java,
                IntArray::class.java, IntArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrays, multipliers, IntArray(width), IntArray(width), minLevel, span) as IntIntEntangledArray
    }

    private fun mergeSumN(arrays: Array<IntIntEntangledArray>, multipliers: IntArray): IntIntEntangledArray {
        var s = 0; for (a in arrays) s += a.size
        return invokeCompanion("mergeSumN",
            arrayOf(Array<IntIntEntangledArray>::class.java, IntArray::class.java,
                IntArray::class.java, IntArray::class.java),
            arrays, multipliers, IntArray(s), IntArray(s)) as IntIntEntangledArray
    }

    @Test fun `denseSumN three weighted`() {
        // L0: 1*1 + 1*2 = 3 ; L1: 2*1 + 3*2 + 1*3 = 11
        val arr = arrayOfIIEA(iiea(0 to 1, 1 to 2), iiea(0 to 1, 1 to 3), iiea(1 to 1))
        assertContent(listOf(0 to 3, 1 to 11), denseSumN(arr, intArrayOf(1, 2, 3), 0, 1))
    }

    @Test fun `denseSumN cancellation drops`() {
        val arr = arrayOfIIEA(iiea(0 to 2, 1 to 2), iiea(0 to 2, 1 to 2))
        assertContent(emptyList(), denseSumN(arr, intArrayOf(1, -1), 0, 1))
    }

    @Test fun `denseSumN holes`() {
        val arr = arrayOfIIEA(iiea(0 to 1, 5 to 3), iiea(5 to 1))
        assertContent(listOf(0 to 1, 5 to 4), denseSumN(arr, intArrayOf(1, 1), 0, 5))
    }

    @Test fun `mergeSumN spread three`() {
        val arr = arrayOfIIEA(iiea(10 to 1, 100000 to 2), iiea(500 to 1, 100000 to 3), iiea(10 to 4, 9000 to 1))
        assertContent(listOf(10 to 5, 500 to 1, 9000 to 1, 100000 to 5), mergeSumN(arr, intArrayOf(1, 1, 1)))
    }

    @Test fun `mergeSumN weighted and cancelling`() {
        val arr = arrayOfIIEA(iiea(10 to 2, 5000 to 3), iiea(10 to 1, 5000 to 3))
        assertContent(listOf(10 to 0).filter { false } + listOf(5000 to 6).let {
            // 10: 2*1 + 1*(-2) = 0 dropped ; 5000: 3*1 + 3*(-2) = -3
            listOf(5000 to -3)
        }, mergeSumN(arr, intArrayOf(1, -2)))
    }

    @Test fun `mergeSumN single tail array`() {
        val arr = arrayOfIIEA(iiea(1 to 1), iiea(100000 to 2))
        assertContent(listOf(1 to 3, 100000 to 2), mergeSumN(arr, intArrayOf(3, 1)))
    }

    // =====================================================================================
    // denseSumOf2 / mergeSumOf2  (unit-multiplier two-array)
    // =====================================================================================

    private fun denseSumOf2(a: IntIntEntangledArray, b: IntIntEntangledArray, minLevel: Int, span: Int): IntIntEntangledArray {
        val width = span + 1
        return invokeCompanion("denseSumOf2",
            arrayOf(IntIntEntangledArray::class.java, IntIntEntangledArray::class.java,
                IntArray::class.java, IntArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            a, b, IntArray(width), IntArray(width), minLevel, span) as IntIntEntangledArray
    }

    private fun mergeSumOf2(a: IntIntEntangledArray, b: IntIntEntangledArray): IntIntEntangledArray {
        val s = a.size + b.size
        return invokeCompanion("mergeSumOf2",
            arrayOf(IntIntEntangledArray::class.java, IntIntEntangledArray::class.java,
                IntArray::class.java, IntArray::class.java),
            a, b, IntArray(s), IntArray(s)) as IntIntEntangledArray
    }

    @Test fun `denseSumOf2 sums shared`() {
        assertContent(listOf(0 to 3, 1 to 5, 2 to 3),
            denseSumOf2(iiea(0 to 2, 1 to 3), iiea(0 to 1, 1 to 2, 2 to 3), 0, 2))
    }

    @Test fun `denseSumOf2 cancellation`() {
        assertContent(listOf(1 to 5), denseSumOf2(iiea(0 to 2, 1 to 5), iiea(0 to -2), 0, 1))
    }

    @Test fun `denseSumOf2 all cancel EMPTY`() {
        Assertions.assertSame(EMPTY, denseSumOf2(iiea(0 to 5), iiea(0 to -5), 0, 0))
    }

    @Test fun `mergeSumOf2 spread sums`() {
        assertContent(listOf(1 to 1, 1000 to 2), mergeSumOf2(iiea(1 to 1), iiea(1000 to 2)))
    }

    @Test fun `mergeSumOf2 overlap and tails`() {
        assertContent(listOf(10 to 3, 5000 to 3, 100000 to 4),
            mergeSumOf2(iiea(10 to 2, 5000 to 3), iiea(10 to 1, 100000 to 4)))
    }

    @Test fun `mergeSumOf2 cancellation drops shared`() {
        assertContent(listOf(5000 to 3, 100000 to 4),
            mergeSumOf2(iiea(10 to 2, 5000 to 3), iiea(10 to -2, 100000 to 4)))
    }

    // =====================================================================================
    // denseSumOfN / mergeSumOfN  (unit-multiplier N-array)
    // =====================================================================================

    private fun denseSumOfN(arrays: Array<IntIntEntangledArray>, minLevel: Int, span: Int): IntIntEntangledArray {
        val width = span + 1
        return invokeCompanion("denseSumOfN",
            arrayOf(Array<IntIntEntangledArray>::class.java,
                IntArray::class.java, IntArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            arrays, IntArray(width), IntArray(width), minLevel, span) as IntIntEntangledArray
    }

    private fun mergeSumOfN(arrays: Array<IntIntEntangledArray>): IntIntEntangledArray {
        var s = 0; for (a in arrays) s += a.size
        return invokeCompanion("mergeSumOfN",
            arrayOf(Array<IntIntEntangledArray>::class.java, IntArray::class.java, IntArray::class.java),
            arrays, IntArray(s), IntArray(s)) as IntIntEntangledArray
    }

    @Test fun `denseSumOfN three`() {
        val arr = arrayOfIIEA(iiea(0 to 1, 1 to 1), iiea(0 to 1, 2 to 1), iiea(1 to 1, 2 to 1))
        assertContent(listOf(0 to 2, 1 to 2, 2 to 2), denseSumOfN(arr, 0, 2))
    }

    @Test fun `denseSumOfN cancel drops`() {
        val arr = arrayOfIIEA(iiea(0 to 5, 1 to 1), iiea(0 to -5))
        assertContent(listOf(1 to 1), denseSumOfN(arr, 0, 1))
    }

    @Test fun `denseSumOfN all cancel EMPTY`() {
        val arr = arrayOfIIEA(iiea(0 to 5), iiea(0 to -5))
        Assertions.assertSame(EMPTY, denseSumOfN(arr, 0, 0))
    }

    @Test fun `mergeSumOfN spread three`() {
        val arr = arrayOfIIEA(iiea(10 to 1), iiea(5000 to 2), iiea(100000 to 3))
        assertContent(listOf(10 to 1, 5000 to 2, 100000 to 3), mergeSumOfN(arr))
    }

    @Test fun `mergeSumOfN overlap sums`() {
        val arr = arrayOfIIEA(iiea(10 to 1, 100000 to 1), iiea(10 to 2, 100000 to 2))
        assertContent(listOf(10 to 3, 100000 to 3), mergeSumOfN(arr))
    }

    @Test fun `mergeSumOfN cancellation`() {
        val arr = arrayOfIIEA(iiea(10 to 5, 100000 to 1), iiea(10 to -5))
        assertContent(listOf(100000 to 1), mergeSumOfN(arr))
    }

    // =====================================================================================
    // cumulativeSet1 / cumulativeSet2 / cumulativeSetN  (private overloads)
    // =====================================================================================

    private fun cumulativeSet1(array: IntIntEntangledArray, dropZeros: Boolean): IntIntEntangledArray {
        val s = array.size
        return invokeCompanion("cumulativeSet1",
            arrayOf(IntIntEntangledArray::class.java, IntArray::class.java, IntArray::class.java, Boolean::class.javaPrimitiveType!!),
            array, IntArray(s), IntArray(s), dropZeros) as IntIntEntangledArray
    }

    private fun cumulativeSet2(a: IntIntEntangledArray, b: IntIntEntangledArray, dropZeros: Boolean): IntIntEntangledArray {
        val s = a.size + b.size
        val minLevel = minOf(a.firstLevel, b.firstLevel)
        val maxLevel = maxOf(a.lastLevel, b.lastLevel)
        return invokeCompanion("cumulativeSet2",
            arrayOf(IntIntEntangledArray::class.java, IntIntEntangledArray::class.java,
                IntArray::class.java, IntArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            a, b, IntArray(s), IntArray(s), minLevel, maxLevel, s, dropZeros) as IntIntEntangledArray
    }

    private fun cumulativeSetN(arrays: Array<IntIntEntangledArray>, n: Int, dropZeros: Boolean): IntIntEntangledArray {
        var s = 0; var minL = Int.MAX_VALUE; var maxL = Int.MIN_VALUE
        for (k in 0 until n) { s += arrays[k].size; minL = minOf(minL, arrays[k].firstLevel); maxL = maxOf(maxL, arrays[k].lastLevel) }
        return invokeCompanion("cumulativeSetN",
            arrayOf(Array<IntIntEntangledArray>::class.java, Int::class.javaPrimitiveType!!,
                IntArray::class.java, IntArray::class.java,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!),
            arrays, n, IntArray(s), IntArray(s), minL, maxL, s, dropZeros) as IntIntEntangledArray
    }

    @Test fun `cumulativeSet1 dropZeros true removes zeros`() {
        assertContent(listOf(1 to 2, 3 to 4), cumulativeSet1(iiea(1 to 2, 2 to 0, 3 to 4, allowZero = true), true))
    }

    @Test fun `cumulativeSet1 dropZeros false keeps zeros`() {
        assertContent(listOf(1 to 2, 2 to 0, 3 to 4),
            cumulativeSet1(iiea(1 to 2, 2 to 0, 3 to 4, allowZero = true), false))
    }

    @Test fun `cumulativeSet1 no zeros returns same instance`() {
        val a = iiea(1 to 1, 2 to 2)
        Assertions.assertSame(a, cumulativeSet1(a, true))
    }

    @Test fun `cumulativeSet1 all zero dropZeros true is EMPTY`() {
        Assertions.assertSame(EMPTY, cumulativeSet1(iiea(1 to 0, 2 to 0, allowZero = true), true))
    }

    @Test fun `cumulativeSet2 dense path overlay`() {
        assertContent(listOf(1 to 10, 2 to 99, 3 to 30),
            cumulativeSet2(iiea(1 to 10, 2 to 20), iiea(2 to 99, 3 to 30), true))
    }

    @Test fun `cumulativeSet2 merge path spread overlay`() {
        assertContent(listOf(10 to 9, 5000 to 3, 999999 to 4),
            cumulativeSet2(iiea(10 to 2, 5000 to 3), iiea(10 to 9, 999999 to 4), true))
    }

    @Test fun `cumulativeSetN dense path`() {
        val arr = arrayOfIIEA(iiea(1 to 1, 2 to 1, 3 to 1), iiea(2 to 2, 3 to 2), iiea(3 to 3))
        assertContent(listOf(1 to 1, 2 to 2, 3 to 3), cumulativeSetN(arr, 3, true))
    }

    @Test fun `cumulativeSetN merge path spread`() {
        val arr = arrayOfIIEA(iiea(10 to 1), iiea(5000 to 2), iiea(100000 to 3))
        assertContent(listOf(10 to 1, 5000 to 2, 100000 to 3), cumulativeSetN(arr, 3, true))
    }

    // =====================================================================================
    // Public edge / state-space gaps not covered by the black-box suite
    // =====================================================================================

    @Test fun `get on empty array returns zero`() {
        Assertions.assertEquals(0, EMPTY[5])
    }

    @Test fun `get present and absent levels`() {
        val a = iiea(1 to 10, 3 to 30, 5 to 50)
        Assertions.assertEquals(10, a[1])
        Assertions.assertEquals(30, a[3])
        Assertions.assertEquals(0, a[2]) // absent
        Assertions.assertEquals(0, a[6]) // above all
        Assertions.assertEquals(0, a[0]) // below all
    }

    @Test fun `get respects window`() {
        val a = iiea(1 to 10, 2 to 20, 3 to 30, 4 to 40)
        val w = a.tail(2) // levels 3,4
        Assertions.assertEquals(30, w[3])
        Assertions.assertEquals(40, w[4])
        Assertions.assertEquals(0, w[1]) // outside window
        Assertions.assertEquals(0, w[2]) // outside window
    }

    @Test fun `get returns stored zero entry`() {
        val a = iiea(1 to 0, 2 to 5, allowZero = true)
        Assertions.assertEquals(0, a[1])
        Assertions.assertEquals(5, a[2])
    }

    @Test fun `div by negative one negates all`() {
        val a = iiea(1 to 3, 2 to -4)
        assertContent(listOf(1 to -3, 2 to 4), a.div(-1))
    }

    @Test fun `times by min value does not special-case`() {
        val a = iiea(1 to 1)
        val r = a.times(Int.MIN_VALUE)
        assertContent(listOf(1 to Int.MIN_VALUE), r)
    }

    @Test fun `serialize deserialize of windowed array round-trips the window only`() {
        val a = iiea(1 to 1, 2 to 2, 3 to 3, 4 to 4, 5 to 5)
        val w = a.tail(3) // levels 3,4,5
        val rt = IntIntEntangledArray.deserialize(w.serialize())
        assertContent(listOf(3 to 3, 4 to 4, 5 to 5), rt)
        Assertions.assertEquals(3, rt.size)
    }

    // =====================================================================================
    // PERFORMANCE + MEMORY  (allocation-focused; generous tolerances, no tight timing gates)
    // =====================================================================================

    private fun bytesPerOp(iterations: Int, op: (Int) -> Unit): Double {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val tid = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(tid)
        for (i in 0 until iterations) op(i)
        val bytes = bean.getThreadAllocatedBytes(tid) - before
        return bytes.toDouble() / iterations
    }

    private fun assumeAllocSupported(): ThreadMXBean {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        return bean
    }

    /**
     * Allocation a fast path adds *beyond* the harness's own lambda-call overhead. A no-op lambda
     * measured the same way establishes the floor (the measurement loop itself can box the loop
     * counter / spin the SAM call site); a fast path that returns a cached singleton or `this` should
     * not allocate measurably more than that floor. Tolerant absolute headroom guards against a gross
     * regression (a fast path that started copying an array) without gating on the exact JIT floor.
     */
    private fun excessBytesPerOp(iterations: Int, op: (Int) -> Unit): Double {
        var sink = 0
        // Allocation per op is stable at its minimum across rounds (JIT warm-up / deopt only ever add
        // noise on top), so take the best-of-N of both the floor and the measured path to cancel
        // transient suite-contention spikes.
        var floor = Double.MAX_VALUE
        var measured = Double.MAX_VALUE
        repeat(5) {
            floor = minOf(floor, bytesPerOp(iterations) { i -> sink += i })
            measured = minOf(measured, bytesPerOp(iterations) { i -> sink += i; op(i) })
        }
        if (sink == Int.MIN_VALUE) println(sink) // keep sink live
        return measured - floor
    }

    @Test fun `perf times by zero allocates nothing (EMPTY fast path)`() {
        assumeAllocSupported()
        val a = iiea(1 to 1, 2 to 2, 3 to 3)
        repeat(100_000) { a.times(0) }
        val bytes = excessBytesPerOp(1_000_000) { a.times(0) }
        Assertions.assertTrue(bytes < 32.0, "times(0) should allocate ~0 bytes/op above the floor, was $bytes")
    }

    @Test fun `perf times by one allocates nothing (same-instance fast path)`() {
        assumeAllocSupported()
        val a = iiea(1 to 1, 2 to 2, 3 to 3)
        repeat(100_000) { a.times(1) }
        val bytes = excessBytesPerOp(1_000_000) { a.times(1) }
        Assertions.assertTrue(bytes < 32.0, "times(1) should allocate ~0 bytes/op above the floor, was $bytes")
    }

    @Test fun `perf div by one allocates nothing (same-instance fast path)`() {
        assumeAllocSupported()
        val a = iiea(1 to 1, 2 to 2, 3 to 3)
        repeat(100_000) { a.div(1) }
        val bytes = excessBytesPerOp(1_000_000) { a.div(1) }
        Assertions.assertTrue(bytes < 32.0, "div(1) should allocate ~0 bytes/op above the floor, was $bytes")
    }

    @Test fun `perf div on empty allocates nothing`() {
        assumeAllocSupported()
        repeat(100_000) { EMPTY.div(7) }
        val bytes = excessBytesPerOp(1_000_000) { EMPTY.div(7) }
        Assertions.assertTrue(bytes < 32.0, "EMPTY.div allocates ~0 bytes/op above the floor, was $bytes")
    }

    @Test fun `perf absolute of all-positive allocates nothing (same instance)`() {
        assumeAllocSupported()
        val a = iiea(1 to 1, 2 to 2, 3 to 3, 4 to 4)
        repeat(100_000) { a.absolute() }
        val bytes = excessBytesPerOp(1_000_000) { a.absolute() }
        Assertions.assertTrue(bytes < 32.0, "absolute() of all-positive should be allocation-free above the floor, was $bytes")
    }

    @Test fun `perf construction on large input completes and is bounded`() {
        assumeAllocSupported()
        val pairs = (0 until 2000).map { it to (it + 1) }
        // warmup
        repeat(50) { IntIntEntangledArray(pairs, allowZero = false) }
        val bytes = bytesPerOp(500) { IntIntEntangledArray(pairs, allowZero = false) }
        // two IntArray(2000) copies dominate (~16KB); allow generous headroom for sort scratch.
        Assertions.assertTrue(bytes < 200_000.0, "construction bytes/op too high: $bytes")
    }

    @Test fun `perf weightedSum on large contiguous inputs is bounded`() {
        assumeAllocSupported()
        val a = IntIntEntangledArray((0 until 2000).map { it to 2 }, allowZero = false)
        val b = IntIntEntangledArray((0 until 2000).map { it to 3 }, allowZero = false)
        val terms = listOf(a to 1, b to 1)
        repeat(200) { IntIntEntangledArray.weightedSum(terms) }
        val bytes = bytesPerOp(2000) { IntIntEntangledArray.weightedSum(terms) }
        // result is 2000 entries (~16KB) plus the boxed term pairs; bound well above that.
        Assertions.assertTrue(bytes < 300_000.0, "weightedSum bytes/op too high: $bytes")
    }

    @Test fun `perf cumulativeSet on large contiguous inputs is bounded`() {
        assumeAllocSupported()
        val a = IntIntEntangledArray((0 until 2000).map { it to 1 }, allowZero = false)
        val b = IntIntEntangledArray((0 until 2000).map { it to 2 }, allowZero = false)
        val inputs = listOf(a, b)
        repeat(200) { IntIntEntangledArray.cumulativeSet(inputs) }
        val bytes = bytesPerOp(2000) { IntIntEntangledArray.cumulativeSet(inputs) }
        Assertions.assertTrue(bytes < 300_000.0, "cumulativeSet bytes/op too high: $bytes")
    }

    @Test fun `perf absoluteDiff on large inputs is bounded`() {
        assumeAllocSupported()
        val a = IntIntEntangledArray((0 until 2000).map { it to 5 }, allowZero = false)
        val b = IntIntEntangledArray((0 until 2000).map { it to 2 }, allowZero = false)
        repeat(200) { IntIntEntangledArray.absoluteDiff(a, b) }
        val bytes = bytesPerOp(2000) { IntIntEntangledArray.absoluteDiff(a, b) }
        Assertions.assertTrue(bytes < 300_000.0, "absoluteDiff bytes/op too high: $bytes")
    }

    @Test fun `perf serialize deserialize round-trip is bounded and correct`() {
        assumeAllocSupported()
        val a = IntIntEntangledArray((0 until 1000).map { it to (it + 1) }, allowZero = false)
        // warmup + correctness
        repeat(50) {
            val rt = IntIntEntangledArray.deserialize(a.serialize())
            Assertions.assertEquals(1000, rt.size)
        }
        val bytes = bytesPerOp(1000) {
            IntIntEntangledArray.deserialize(a.serialize())
        }
        // round-trip allocates the serialized buffer + two IntArray(1000); generous bound.
        Assertions.assertTrue(bytes < 200_000.0, "serialize/deserialize bytes/op too high: $bytes")
        // final correctness check via cancellation identity
        Assertions.assertSame(EMPTY, ws(IntIntEntangledArray.deserialize(a.serialize()) to 1, a to -1))
    }

    @Test fun `perf weightedSum spread merge path completes within budget`() {
        assumeAllocSupported()
        val a = IntIntEntangledArray((0 until 1000).map { (it * 1000) to 1 }, allowZero = false)
        val b = IntIntEntangledArray((0 until 1000).map { (it * 1000 + 1) to 2 }, allowZero = false)
        val terms = listOf(a to 1, b to 1)
        val t0 = System.nanoTime()
        repeat(2000) { IntIntEntangledArray.weightedSum(terms) }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        Assertions.assertTrue(elapsedMs < 5000, "spread weightedSum took too long: ${elapsedMs}ms")
        // sanity: result has 2000 entries (union of two disjoint 1000-entry spreads)
        Assertions.assertEquals(2000, IntIntEntangledArray.weightedSum(terms).size)
    }

    @Test fun `sanity all private paths agree with public weightedSum`() {
        // cross-validates the reflection-driven private helpers against the public API for one case.
        val a = iiea(0 to 2, 1 to 3, 2 to 4)
        val b = iiea(0 to 1, 1 to 1, 2 to 1)
        val viaDense = denseSum2(a, b, 1, 1, 0, 2)
        val viaPublic = ws(a to 1, b to 1)
        Assertions.assertSame(EMPTY, ws(viaDense to 1, viaPublic to -1))
        @Suppress("UNUSED_VARIABLE") val unused = abs(-1) // keep abs import used
    }
}
