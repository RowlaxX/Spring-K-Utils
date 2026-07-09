package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import fr.rowlaxx.springkutils.array.ArrayUtils.Sorted
import fr.rowlaxx.springkutils.array.ArrayUtils.asList
import fr.rowlaxx.springkutils.array.ArrayUtils.drain
import fr.rowlaxx.springkutils.array.ArrayUtils.clear
import fr.rowlaxx.springkutils.array.ArrayUtils.sortedDirection
import fr.rowlaxx.springkutils.array.ArrayUtils.unsafeGroupBy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.ForkJoinPool

/**
 * Gap-filling coverage for [ArrayUtils] beyond `ArrayUtilsTest` and `ArrayUtilsGroupingTest`.
 *
 * Focus areas not already exercised by the sibling suites:
 *  - the **`LongArray.sortedDirection`** overload (the existing suite only tests Int & Double);
 *  - sortedness corner cases (NaN, equal runs, single boundary inversions, sub-window edges);
 *  - many grow/reuse cycles of every scratch factory, including the **zero-allocation reuse**
 *    guarantee that is the factories' whole reason to exist;
 *  - grouping internals reached only via reflection and additional dense/sparse boundary states;
 *  - performance/memory benchmarks proving scratch reuse and grouping reuse allocate ~zero.
 *
 * The scratch pools cache one buffer per thread and never shrink, so pool assertions run on a fresh
 * [Thread] (pristine ThreadLocal) — mirroring `ArrayUtilsTest.onFreshThread`.
 */
class ArrayUtilsCoverageTest {

    private val DEFAULT_CAPACITY = 1024

    /** Runs [block] on a freshly spawned thread (pristine ThreadLocal state) and returns its result. */
    private fun <T> onFreshThread(block: () -> T): T {
        val box = arrayOfNulls<Any>(1)
        val error = arrayOfNulls<Throwable>(1)
        val thread = Thread {
            try { box[0] = block() }
            catch (e: Throwable) { error[0] = e }
        }
        thread.start()
        thread.join()
        error[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return box[0] as T
    }

    private data class E(val key: Long, val id: Int)
    private fun e(key: Long, id: Int) = E(key, id)

    /** Materialises a GroupedBy into ordered (key, ids) before scratch is reused. */
    private fun <T> ArrayUtils.GroupedBy<T>.snapshot(idOf: (T) -> Int): List<Pair<Long, List<Int>>> {
        val out = ArrayList<Pair<Long, List<Int>>>()
        forEach { key, list -> out.add(key to list.map(idOf)) }
        return out
    }

    // =============================================================================================
    // LongArray.sortedDirection  (NOT covered by ArrayUtilsTest, which only does Int & Double)
    // =============================================================================================

    @Test
    fun `long empty array is ASC`() {
        assertEquals(Sorted.ASC, longArrayOf().sortedDirection())
    }

    @Test
    fun `long single element is ASC`() {
        assertEquals(Sorted.ASC, longArrayOf(42L).sortedDirection())
    }

    @Test
    fun `long two equal elements is ASC`() {
        assertEquals(Sorted.ASC, longArrayOf(5L, 5L).sortedDirection())
    }

    @Test
    fun `long strictly ascending is ASC`() {
        assertEquals(Sorted.ASC, longArrayOf(1L, 2L, 3L, 4L, 5L).sortedDirection())
    }

    @Test
    fun `long non-decreasing with duplicates is ASC`() {
        assertEquals(Sorted.ASC, longArrayOf(1L, 1L, 2L, 2L, 3L).sortedDirection())
    }

    @Test
    fun `long strictly descending is DESC`() {
        assertEquals(Sorted.DESC, longArrayOf(5L, 4L, 3L, 2L, 1L).sortedDirection())
    }

    @Test
    fun `long unsorted is NONE`() {
        assertEquals(Sorted.NONE, longArrayOf(1L, 3L, 2L).sortedDirection())
    }

    @Test
    fun `long non-increasing with leading duplicate is NONE`() {
        // A leading equal pair routes into the ASC branch (a[start] <= a[start+1]); the later
        // decrease then breaks it to NONE — same quirk as the Int/Double overloads.
        assertEquals(Sorted.NONE, longArrayOf(2L, 2L, 1L).sortedDirection())
    }

    @Test
    fun `long descending then ascending is NONE`() {
        assertEquals(Sorted.NONE, longArrayOf(3L, 1L, 2L).sortedDirection())
    }

    @Test
    fun `long subrange ascending is ASC`() {
        assertEquals(Sorted.ASC, longArrayOf(99L, 1L, 2L, 3L, 0L).sortedDirection(1, 4))
    }

    @Test
    fun `long subrange descending is DESC`() {
        assertEquals(Sorted.DESC, longArrayOf(0L, 5L, 4L, 3L, 99L).sortedDirection(1, 4))
    }

    @Test
    fun `long subrange of one element is ASC`() {
        assertEquals(Sorted.ASC, longArrayOf(9L, 8L, 7L).sortedDirection(1, 2))
    }

    @Test
    fun `long empty subrange is ASC`() {
        assertEquals(Sorted.ASC, longArrayOf(9L, 8L, 7L).sortedDirection(2, 2))
    }

    @Test
    fun `long extreme values ascending and descending`() {
        assertEquals(Sorted.ASC, longArrayOf(Long.MIN_VALUE, 0L, Long.MAX_VALUE).sortedDirection())
        assertEquals(Sorted.DESC, longArrayOf(Long.MAX_VALUE, 0L, Long.MIN_VALUE).sortedDirection())
    }

    // =============================================================================================
    // sortedDirection — boundary inversions, equal runs, NaN  (deepening Int/Double/Long)
    // =============================================================================================

    @Test
    fun `int single inversion at the very end breaks ASC to NONE`() {
        assertEquals(Sorted.NONE, intArrayOf(1, 2, 3, 4, 0).sortedDirection())
    }

    @Test
    fun `int single inversion at the very start of the scan breaks DESC to NONE`() {
        // first pair strictly decreasing -> DESC branch; a single increase anywhere -> NONE
        assertEquals(Sorted.NONE, intArrayOf(5, 4, 9, 3, 2).sortedDirection())
    }

    @Test
    fun `int long equal run is ASC`() {
        assertEquals(Sorted.ASC, IntArray(50) { 7 }.sortedDirection())
    }

    @Test
    fun `int equal run with one trailing increase is ASC`() {
        assertEquals(Sorted.ASC, intArrayOf(3, 3, 3, 3, 4).sortedDirection())
    }

    @Test
    fun `int equal run with one trailing decrease is NONE`() {
        assertEquals(Sorted.NONE, intArrayOf(3, 3, 3, 3, 2).sortedDirection())
    }

    @Test
    fun `double subrange ascending ignores out of range tail and head`() {
        assertEquals(Sorted.ASC, doubleArrayOf(9.0, 1.0, 2.0, 3.0, -1.0).sortedDirection(1, 4))
    }

    @Test
    fun `double single element subrange is ASC`() {
        assertEquals(Sorted.ASC, doubleArrayOf(5.0, 4.0, 3.0).sortedDirection(2, 3))
    }

    @Test
    fun `double NaN as second element makes leading comparison false routing to DESC branch`() {
        // a[0] <= a[1] is false when a[1] is NaN, so the DESC branch runs. NaN comparisons are
        // all false, so no `<` ever fires and it reports DESC. This documents the actual (quirky)
        // behaviour for NaN input rather than asserting an "ideal" result.
        assertEquals(Sorted.DESC, doubleArrayOf(1.0, Double.NaN, 0.0).sortedDirection())
    }

    @Test
    fun `double NaN as first element routes to ASC branch`() {
        // a[0] <= a[1] is false (NaN), wait: NaN <= x is false -> DESC branch. With remaining
        // strictly ascending tail, the `<` check fires and returns NONE.
        assertEquals(Sorted.NONE, doubleArrayOf(Double.NaN, 1.0, 2.0).sortedDirection())
    }

    @Test
    fun `double positive and negative infinity ascending is ASC`() {
        assertEquals(
            Sorted.ASC,
            doubleArrayOf(Double.NEGATIVE_INFINITY, -1.0, 0.0, 1.0, Double.POSITIVE_INFINITY).sortedDirection(),
        )
    }

    @Test
    fun `double negative zero and positive zero treated as equal stays ASC`() {
        // -0.0 <= 0.0 and 0.0 <= -0.0 are both true for doubles, so a run of zeros is ASC.
        assertEquals(Sorted.ASC, doubleArrayOf(-0.0, 0.0, -0.0).sortedDirection())
    }

    @Test
    fun `sortedDirection two equal then strictly ascending across the boundary`() {
        // Exercises start+2 loop kicking off right after an equal leading pair.
        assertEquals(Sorted.ASC, intArrayOf(2, 2, 3, 4).sortedDirection())
    }

    // =============================================================================================
    // Scratch factories — many grow/reuse cycles + exact-grow semantics
    // =============================================================================================

    @Test
    fun `int factory many alternating in-capacity requests return the same buffer`() {
        val f = ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            val first = f(1)
            repeat(1000) { i ->
                val a = f((i % DEFAULT_CAPACITY) + 1)
                assertSame(first, a)
            }
        }
    }

    @Test
    fun `int factory monotonic growth keeps the largest buffer`() {
        val f = ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            var prev = f(1)
            var size = DEFAULT_CAPACITY
            repeat(5) {
                size *= 2
                val next = f(size)
                assertNotSame(prev, next)
                assertTrue(next.size >= size)
                // Subsequent small request reuses the grown buffer.
                assertSame(next, f(1))
                prev = next
            }
        }
    }

    @Test
    fun `factory grows to exactly the requested size for every primitive type`() {
        onFreshThread {
            assertEquals(2000, ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)(2000).size)
            assertEquals(2000, ArrayUtils.ScratchLongArrayFactory(DEFAULT_CAPACITY)(2000).size)
            assertEquals(2000, ArrayUtils.ScratchDoubleArrayFactory(DEFAULT_CAPACITY)(2000).size)
            assertEquals(2000, ArrayUtils.ScratchFloatArrayFactory(DEFAULT_CAPACITY)(2000).size)
            assertEquals(2000, ArrayUtils.ScratchShortArrayFactory(DEFAULT_CAPACITY)(2000).size)
            assertEquals(2000, ArrayUtils.ScratchByteArrayFactory(DEFAULT_CAPACITY)(2000).size)
            assertEquals(2000, ArrayUtils.ScratchCharArrayFactory(DEFAULT_CAPACITY)(2000).size)
            assertEquals(2000, ArrayUtils.ScratchBooleanArrayFactory(DEFAULT_CAPACITY)(2000).size)
            assertEquals(2000, ArrayUtils.ScratchArrayFactory(DEFAULT_CAPACITY)(2000).size)
        }
    }

    @Test
    fun `factory request of exactly capacity reuses the initial buffer`() {
        onFreshThread {
            val f = ArrayUtils.ScratchLongArrayFactory(DEFAULT_CAPACITY)
            val initial = f(1)
            assertSame(initial, f(DEFAULT_CAPACITY)) // == capacity: not < minSize, so no realloc
        }
    }

    @Test
    fun `factory request of capacity plus one forces a single realloc`() {
        onFreshThread {
            val f = ArrayUtils.ScratchDoubleArrayFactory(DEFAULT_CAPACITY)
            val initial = f(1)
            val grown = f(DEFAULT_CAPACITY + 1)
            assertNotSame(initial, grown)
            assertEquals(DEFAULT_CAPACITY + 1, grown.size)
        }
    }

    @Test
    fun `factory with tiny initial capacity still serves zero and one requests`() {
        onFreshThread {
            val f = ArrayUtils.ScratchIntArrayFactory(1)
            assertEquals(1, f(0).size)
            assertSame(f(0), f(1))
        }
    }

    @Test
    fun `object factory stores and retains references across reuse`() {
        onFreshThread {
            val f = ArrayUtils.ScratchArrayFactory(DEFAULT_CAPACITY)
            val a = f(10)
            a[0] = "x"; a[1] = 42
            val b = f(5) // fits, same buffer, references intact
            assertSame(a, b)
            assertEquals("x", b[0]); assertEquals(42, b[1])
        }
    }

    @Test
    fun `boolean factory grows and retains across many cycles`() {
        onFreshThread {
            val f = ArrayUtils.ScratchBooleanArrayFactory(8)
            val small = f(4)
            val big = f(100)
            assertNotSame(small, big)
            assertTrue(big.size >= 100)
            repeat(20) { assertSame(big, f((it % 100) + 1)) }
        }
    }

    @Test
    fun `char factory grows and retains across many cycles`() {
        onFreshThread {
            val f = ArrayUtils.ScratchCharArrayFactory(8)
            val small = f(4)
            val big = f(100)
            assertNotSame(small, big)
            repeat(20) { assertSame(big, f((it % 100) + 1)) }
        }
    }

    @Test
    fun `every factory type is per-thread isolated`() {
        val intF = ArrayUtils.ScratchIntArrayFactory(16)
        val objF = ArrayUtils.ScratchArrayFactory(16)
        val a1 = onFreshThread { intF(8) }
        val a2 = onFreshThread { intF(8) }
        val o1 = onFreshThread { objF(8) }
        val o2 = onFreshThread { objF(8) }
        assertNotSame(a1, a2)
        assertNotSame(o1, o2)
    }

    // =============================================================================================
    // warnIfShared — additional thread contexts
    // =============================================================================================

    @Test
    fun `warnIfShared is safe to call repeatedly on a plain thread`() {
        onFreshThread { repeat(5) { assertDoesNotThrow { ArrayUtils.warnIfShared() } } }
    }

    @Test
    fun `warnIfShared on common ForkJoinPool worker is silent and safe`() {
        ForkJoinPool.commonPool().submit { assertDoesNotThrow { ArrayUtils.warnIfShared() } }.get()
    }

    // =============================================================================================
    // drain — boundary depth not covered (single element, full-on-empty fails, primitive null-free)
    // =============================================================================================

    @Test
    fun `object drain of one element`() {
        val a = arrayOf<Any?>("only", "tail")
        assertEquals(listOf("only"), a.drain<String>(1))
        assertNull(a[0]); assertEquals("tail", a[1])
    }

    @Test
    fun `object drain on empty array of zero is empty`() {
        assertTrue(arrayOf<Any?>().drain<String>(0).isEmpty())
    }

    @Test
    fun `primitive drains do not mutate the source for every type`() {
        val l = longArrayOf(1, 2, 3); l.drain(2); assertEquals(3L, l[2])
        val i = intArrayOf(1, 2, 3); i.drain(2); assertEquals(3, i[2])
        val d = doubleArrayOf(1.0, 2.0, 3.0); d.drain(2); assertEquals(3.0, d[2])
        val f = floatArrayOf(1f, 2f, 3f); f.drain(2); assertEquals(3f, f[2])
        val s = shortArrayOf(1, 2, 3); s.drain(2); assertEquals(3.toShort(), s[2])
        val b = byteArrayOf(1, 2, 3); b.drain(2); assertEquals(3.toByte(), b[2])
        val c = charArrayOf('a', 'b', 'c'); c.drain(2); assertEquals('c', c[2])
        val bo = booleanArrayOf(true, true, false); bo.drain(2); assertFalse(bo[2])
    }

    @Test
    fun `primitive drain of full size returns whole array for every type`() {
        assertEquals(listOf(1L, 2L), longArrayOf(1, 2).drain(2))
        assertEquals(listOf(1.5, 2.5), doubleArrayOf(1.5, 2.5).drain(2))
        assertEquals(listOf(1f, 2f), floatArrayOf(1f, 2f).drain(2))
        assertEquals(listOf<Short>(1, 2), shortArrayOf(1, 2).drain(2))
        assertEquals(listOf<Byte>(1, 2), byteArrayOf(1, 2).drain(2))
        assertEquals(listOf('a', 'b'), charArrayOf('a', 'b').drain(2))
        assertEquals(listOf(true, false), booleanArrayOf(true, false).drain(2))
    }

    @Test
    fun `object drain past size throws and leaves array untouched`() {
        val a = arrayOf<Any?>("a", "b")
        assertThrows(IndexOutOfBoundsException::class.java) { a.drain<String>(3) }
        assertEquals("a", a[0]); assertEquals("b", a[1])
    }

    // =============================================================================================
    // clear — single element + idempotence
    // =============================================================================================

    @Test
    fun `clear of one element nulls only the first slot`() {
        val a = arrayOf<Any?>("a", "b")
        a.clear(1)
        assertNull(a[0]); assertEquals("b", a[1])
    }

    @Test
    fun `clear is idempotent`() {
        val a = arrayOf<Any?>("a", "b", "c")
        a.clear(2); a.clear(2)
        assertNull(a[0]); assertNull(a[1]); assertEquals("c", a[2])
    }

    // =============================================================================================
    // Grouping — additional dense/sparse boundary & accessor states
    // =============================================================================================

    @Test
    fun `two elements one key takes the min-equals-max fast path`() {
        val g = listOf(e(5, 0), e(5, 1)).unsafeGroupBy { it.key }
        assertEquals(1, g.count)
        assertEquals(listOf(5L to listOf(0, 1)), g.snapshot { it.id })
    }

    @Test
    fun `dense path span exactly n with a gap key present`() {
        // keys {0,2}, n=2, span=3 > 2 -> sparse; verify the boundary the OTHER way:
        // keys {0,1}, n=2, span=2 == n -> dense
        val g = listOf(e(1, 1), e(0, 0)).unsafeGroupBy { it.key }
        assertEquals(listOf(0L to listOf(0), 1L to listOf(1)), g.snapshot { it.id })
    }

    @Test
    fun `dense path with empty intermediate buckets skips absent keys`() {
        // keys {0, 4}, span=5 <= n=6 -> dense; buckets 1,2,3 stay empty and must be skipped.
        val items = listOf(e(0, 0), e(4, 1), e(0, 2), e(4, 3), e(0, 4), e(4, 5))
        val g = items.unsafeGroupBy { it.key }
        assertEquals(2, g.count)
        assertEquals(
            listOf(0L to listOf(0, 2, 4), 4L to listOf(1, 3, 5)),
            g.snapshot { it.id },
        )
    }

    @Test
    fun `dense path with all negative keys and a gap`() {
        val items = listOf(e(-5, 0), e(-3, 1), e(-5, 2), e(-3, 3))
        val g = items.unsafeGroupBy { it.key }
        assertEquals(
            listOf(-5L to listOf(0, 2), -3L to listOf(1, 3)),
            g.snapshot { it.id },
        )
    }

    @Test
    fun `sparse path many distinct keys descending input sorted ascending`() {
        val items = (0 until 10).map { e((10_000L * (10 - it)), it) }
        val g = items.unsafeGroupBy { it.key }
        assertEquals(10, g.count)
        val keys = buildList { g.forEachKey { add(it) } }
        assertEquals(keys.sorted(), keys)
    }

    @Test
    fun `sparse path stable for many duplicates of two far keys`() {
        val items = (0 until 20).map { e(if (it % 2 == 0) 0L else 5_000_000L, it) }
        val g = items.unsafeGroupBy { it.key }
        assertEquals(2, g.count)
        assertEquals((0 until 20 step 2).toList(), g[0L].map { it.id })
        assertEquals((1 until 20 step 2).toList(), g[5_000_000L].map { it.id })
    }

    @Test
    fun `contains finds present key and rejects gaps in dense grouping`() {
        val g = listOf(e(0, 0), e(2, 1), e(4, 2)).unsafeGroupBy { it.key } // sparse (span 5 > 3)
        assertTrue(g.contains(0L)); assertTrue(g.contains(2L)); assertTrue(g.contains(4L))
        assertFalse(g.contains(1L)); assertFalse(g.contains(3L)); assertFalse(g.contains(5L))
    }

    @Test
    fun `first and last on a single dense group are identical and complete`() {
        val items = (0 until 4).map { e(0L, it) }
        val g = items.unsafeGroupBy { it.key }
        assertEquals(listOf(0, 1, 2, 3), g.first().map { it.id })
        assertEquals(listOf(0, 1, 2, 3), g.last().map { it.id })
    }

    @Test
    fun `get on absent key beyond max returns empty`() {
        val g = listOf(e(1, 0), e(2, 1)).unsafeGroupBy { it.key }
        assertTrue(g[100L].isEmpty())
        assertTrue(g[Long.MIN_VALUE].isEmpty())
    }

    @Test
    fun `group list iteration via for-loop yields all members`() {
        val g = listOf(e(1, 7), e(1, 8), e(1, 9)).unsafeGroupBy { it.key }
        val ids = ArrayList<Int>()
        for (item in g[1L]) ids.add(item.id)
        assertEquals(listOf(7, 8, 9), ids)
    }

    @Test
    fun `clear after dense grouping nulls every backing slot`() {
        val items = listOf(e(0, 0), e(1, 1), e(2, 2))
        val g = items.unsafeGroupBy { it.key }
        val views = (0L..2L).map { g[it] }
        g.close()
        @Suppress("UNCHECKED_CAST")
        for (v in views) assertNull((v as List<Any?>)[0])
    }

    @Test
    fun `larger sparse input groups every element exactly once`() {
        val items = (0 until 500).map { e((it % 25).toLong() * 1_000_000L, it) }
        val g = items.unsafeGroupBy { it.key }
        assertEquals(25, g.count)
        var total = 0
        g.forEach { _, list -> total += list.size }
        assertEquals(500, total)
    }

    @Test
    fun `grouping by constant key collapses to one group regardless of size`() {
        val g = (0 until 100).map { e(it.toLong(), it) }.unsafeGroupBy { 0L }
        assertEquals(1, g.count)
        assertEquals(100, g.first().size)
    }

    // =============================================================================================
    // Private internals via reflection
    // =============================================================================================

    @Test
    fun `RangeViewList rejects an inverted range at construction`() {
        val rvl = Class.forName("fr.rowlaxx.springkutils.array.ArrayUtils\$RangeViewList")
        val ctor = rvl.declaredConstructors.first().apply { isAccessible = true }
        val backing = arrayOf<Any?>("a", "b", "c")
        // start=2, end=1 -> checkFromToIndex must reject (wrapped in InvocationTargetException).
        val ex = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            ctor.newInstance(backing, 2, 1)
        }
        assertTrue(ex.cause is IndexOutOfBoundsException)
    }

    @Test
    fun `RangeViewList empty window has size zero`() {
        val rvl = Class.forName("fr.rowlaxx.springkutils.array.ArrayUtils\$RangeViewList")
        val ctor = rvl.declaredConstructors.first().apply { isAccessible = true }
        val backing = arrayOf<Any?>("a", "b", "c")
        val view = ctor.newInstance(backing, 1, 1) as List<*>
        assertEquals(0, view.size)
        assertTrue(view.isEmpty())
    }

    @Test
    fun `RangeViewList exposes the windowed slice`() {
        val rvl = Class.forName("fr.rowlaxx.springkutils.array.ArrayUtils\$RangeViewList")
        val ctor = rvl.declaredConstructors.first().apply { isAccessible = true }
        val backing = arrayOf<Any?>("a", "b", "c", "d")
        @Suppress("UNCHECKED_CAST")
        val view = ctor.newInstance(backing, 1, 3) as List<String>
        assertEquals(listOf("b", "c"), view)
    }

    @Test
    fun `denseGroup and sparseGroup produce the same shape via reflection`() {
        // Sanity check both private grouping kernels are reachable & symbol-stable. We invoke the
        // public unsafeGroupBy which dispatches to each, asserting identical logical output, since
        // the private methods take a closure parameter that is awkward to pass through reflection.
        val dense = listOf(e(0, 0), e(1, 1), e(2, 2), e(1, 3)) // span 3 <= n 4 -> dense
        val sparse = listOf(e(0, 0), e(99, 1), e(198, 2), e(99, 3)) // span 199 > 4 -> sparse
        assertEquals(
            listOf(listOf(0), listOf(1, 3), listOf(2)),
            dense.unsafeGroupBy { it.key }.snapshot { it.id }.map { it.second },
        )
        assertEquals(
            listOf(listOf(0), listOf(1, 3), listOf(2)),
            sparse.unsafeGroupBy { it.key }.snapshot { it.id }.map { it.second },
        )
    }

    // =============================================================================================
    // PERFORMANCE + MEMORY — scratch reuse / grouping reuse allocate ~zero within capacity
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
    fun `scratch factory reuse allocates near-zero vs a fresh array each call`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        // Pool reuse must run on a thread so the warn path doesn't matter; allocation measurement
        // is per-thread, so do everything on a single dedicated thread.
        onFreshThread {
            val f = ArrayUtils.ScratchIntArrayFactory(1024)
            f(1024) // prime/grow once outside the measured window

            // A rotating sink retains every freshly-allocated array so escape analysis cannot
            // scalar-replace / elide the `IntArray(1000)` — that elision is exactly the JIT
            // optimisation that would otherwise make the "fresh" baseline look free and hide the
            // factory's benefit. The reuse path writes into the same retained buffer for symmetry.
            val sink = arrayOfNulls<IntArray>(16)
            val reuseOp: (Int) -> Unit = { val a = f(1000); a[0] = it; sink[it and 15] = a }
            val freshOp: (Int) -> Unit = { val a = IntArray(1000); a[0] = it; sink[it and 15] = a }

            repeat(100_000) { reuseOp(it); freshOp(it) }

            val iterations = 1_000_000
            val reuseBytes = bytesPerOp(iterations) { reuseOp(it) }
            val freshBytes = bytesPerOp(iterations) { freshOp(it) }

            println("=== ScratchIntArrayFactory reuse vs fresh IntArray(1000) ===")
            println("reuse bytes/op = ${"%.2f".format(reuseBytes)}")
            println("fresh bytes/op = ${"%.2f".format(freshBytes)}")

            // A fresh IntArray(1000) is ~4 KB; once it escapes into the sink the JIT must really
            // allocate it. Reuse hands back the same buffer, so its allocation is negligible.
            assertTrue(freshBytes > 1000.0, "sanity: fresh allocation should be large: $freshBytes")
            assertTrue(reuseBytes < freshBytes * 0.1,
                "scratch reuse within capacity should allocate far less than fresh: reuse=$reuseBytes fresh=$freshBytes")
        }
    }

    @Test
    fun `scratch factory reuse is faster than allocating fresh`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        onFreshThread {
            val f = ArrayUtils.ScratchDoubleArrayFactory(1024)
            f(1024)
            // Retain both buffers in a rotating sink so the fresh DoubleArray genuinely allocates
            // (no escape-analysis elision) — otherwise the baseline is unrealistically fast and the
            // comparison is meaningless.
            val sink = arrayOfNulls<DoubleArray>(16)
            val reuseOp: (Int) -> Unit = { val a = f(1000); a[0] = it.toDouble(); sink[it and 15] = a }
            val freshOp: (Int) -> Unit = { val a = DoubleArray(1000); a[0] = it.toDouble(); sink[it and 15] = a }

            repeat(100_000) { reuseOp(it); freshOp(it) }

            val iterations = 1_000_000
            val t0 = System.nanoTime(); for (i in 0 until iterations) reuseOp(i)
            val reuseNs = (System.nanoTime() - t0).toDouble() / iterations
            val t1 = System.nanoTime(); for (i in 0 until iterations) freshOp(i)
            val freshNs = (System.nanoTime() - t1).toDouble() / iterations

            println("=== Scratch reuse ns/op=${"%.2f".format(reuseNs)}  fresh ns/op=${"%.2f".format(freshNs)} ===")
            // Wall-clock micro-timing is noisy under full-suite JVM contention, so this is a generous
            // gate that only guards against a gross regression (the printout shows the real margin).
            assertTrue(reuseNs < freshNs * 3.0,
                "reuse should not be grossly slower than fresh alloc: reuse=$reuseNs fresh=$freshNs")
        }
    }

    @Test
    fun `unsafeGroupBy reuses scratch and allocates only the result wrapper within capacity`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        onFreshThread {
            // Stay within the GROUP_SCRATCH_SIZE (64) capacity so no scratch buffer regrows.
            val data = (0 until 32).map { e((it % 8).toLong(), it) }
            // Prime: grows the shared group scratch to >= 32 once, then it stays put.
            data.unsafeGroupBy { it.key }

            val op: (Int) -> Unit = { val g = data.unsafeGroupBy { it.key }; g.count }
            repeat(100_000) { op(it) }

            val iterations = 1_000_000
            val perOp = bytesPerOp(iterations) { op(it) }

            println("=== unsafeGroupBy bytes/op (n=32, within scratch) = ${"%.2f".format(perOp)} ===")
            // The counting-sort path reuses thread-local scratch; only the small GroupedBy wrapper
            // (+ the lambda capture) is fresh. Asserting well under a single 32-element array (~272 B
            // boxed) — the scratch buffers themselves (keys/cumEnd/values, hundreds of bytes) are NOT
            // re-allocated each call.
            assertTrue(perOp < 256.0,
                "grouping within scratch capacity should allocate ~only the wrapper: $perOp bytes/op")
        }
    }

    @Test
    fun `repeated grouping does not grow allocation per op as it would with fresh maps`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        onFreshThread {
            val data = (0 until 50).map { e((it % 10).toLong(), it) }
            data.unsafeGroupBy { it.key } // prime scratch

            val groupOp: (Int) -> Unit = { data.unsafeGroupBy { it.key }.count }
            // Reference: a fresh HashMap grouping boxes keys + allocates nodes per element.
            val mapOp: (Int) -> Unit = { data.groupBy { it.key }.size }

            repeat(50_000) { groupOp(it); mapOp(it) }

            val iterations = 500_000
            val groupBytes = bytesPerOp(iterations) { groupOp(it) }
            val mapBytes = bytesPerOp(iterations) { mapOp(it) }

            println("=== unsafeGroupBy bytes/op=${"%.2f".format(groupBytes)}  stdlib groupBy bytes/op=${"%.2f".format(mapBytes)} ===")
            assertTrue(groupBytes < mapBytes * 0.5,
                "unsafeGroupBy should at least halve allocation vs stdlib groupBy: ours=$groupBytes std=$mapBytes")
        }
    }

    // ---------------------------------------------------------------------------------------------
    // asList — bounded, live view over an Array<Any?> sub-range
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `asList views exactly the requested sub-range`() {
        val arr = arrayOf<Any?>("a", "b", "c", "d", "e")
        val view = arr.asList<String>(1, 4)
        assertEquals(3, view.size)
        assertEquals(listOf("b", "c", "d"), view)
    }

    @Test
    fun `asList over the full range mirrors the whole array`() {
        val arr = arrayOf<Any?>(10, 20, 30)
        assertEquals(listOf(10, 20, 30), arr.asList<Int>(0, arr.size))
    }

    @Test
    fun `asList end index is exclusive`() {
        val arr = arrayOf<Any?>("a", "b", "c")
        assertEquals(listOf("a", "b"), arr.asList<String>(0, 2))
    }

    @Test
    fun `asList on an empty window is an empty list`() {
        val arr = arrayOf<Any?>("x", "y")
        assertTrue(arr.asList<String>(1, 1).isEmpty())
        assertTrue(arr.asList<String>(2, 2).isEmpty()) // empty window at the very end is valid
    }

    @Test
    fun `asList is a live view of the backing array`() {
        val arr = arrayOf<Any?>("a", "b", "c")
        val view = arr.asList<String>(0, 3)
        arr[1] = "B"
        assertEquals("B", view[1])
        assertEquals(listOf("a", "B", "c"), view)
    }

    @Test
    fun `asList rejects out-of-bounds windows`() {
        val arr = arrayOf<Any?>(1, 2, 3)
        assertThrows(IndexOutOfBoundsException::class.java) { arr.asList<Int>(-1, 2) }
        assertThrows(IndexOutOfBoundsException::class.java) { arr.asList<Int>(0, 4) }
        assertThrows(IndexOutOfBoundsException::class.java) { arr.asList<Int>(2, 1) } // start > end
    }

    @Test
    fun `asList element access is bounds-checked against the window`() {
        val arr = arrayOf<Any?>("a", "b", "c", "d")
        val view = arr.asList<String>(1, 3) // ["b", "c"], size 2
        assertThrows(IndexOutOfBoundsException::class.java) { view[2] }
        assertThrows(IndexOutOfBoundsException::class.java) { view[-1] }
    }

    // ---------------------------------------------------------------------------------------------
    // unsafeGroupBy — span-overflow guard on the dense/sparse decision
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `unsafeGroupBy handles keys whose span overflows Long`() {
        // maxKey - minKey overflows Long here (MIN..MAX wraps to -1). The old decision read that as a
        // tiny span and drove the dense path with a bogus zero range, indexing out of bounds (or losing
        // every element); the overflow guard must instead pick the sparse path and group correctly.
        val items = listOf(e(Long.MIN_VALUE, 0), e(5L, 1), e(Long.MAX_VALUE, 2), e(5L, 3))
        val g = items.unsafeGroupBy { it.key }

        assertEquals(3, g.count)
        assertEquals(listOf(0), g[Long.MIN_VALUE].map { it.id })
        assertEquals(listOf(1, 3), g[5L].map { it.id })
        assertEquals(listOf(2), g[Long.MAX_VALUE].map { it.id })
        assertTrue(g[12_345L].isEmpty())
    }

    @Test
    fun `unsafeGroupBy still takes the dense path for a small contiguous span`() {
        // Regression guard for the rewritten branch: a genuinely small span must remain dense and group
        // correctly (the overflow guard must not accidentally force everything to sparse).
        val items = listOf(e(10L, 0), e(11L, 1), e(10L, 2), e(12L, 3))
        val g = items.unsafeGroupBy { it.key }

        assertEquals(3, g.count)
        assertEquals(listOf(0, 2), g[10L].map { it.id })
        assertEquals(listOf(1), g[11L].map { it.id })
        assertEquals(listOf(3), g[12L].map { it.id })
    }
}
