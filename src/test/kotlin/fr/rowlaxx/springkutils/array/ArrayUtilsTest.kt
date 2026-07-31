package fr.rowlaxx.springkutils.array

import fr.rowlaxx.springkutils.array.ArrayUtils.Sorted
import fr.rowlaxx.springkutils.array.ArrayUtils.clear
import fr.rowlaxx.springkutils.array.ArrayUtils.drain
import fr.rowlaxx.springkutils.array.ArrayUtils.sortedDirection
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ForkJoinPool

/**
 * Tests for [ArrayUtils]: the [sortedDirection] classifiers and the per-thread scratch-array pools.
 *
 * The scratch pools cache one buffer per thread, growing but never shrinking, so their behavior is
 * only deterministic against a thread whose [ThreadLocal] has not yet been touched. Every pool
 * assertion therefore runs on a brand-new [Thread] via [onFreshThread] so it starts from the pool's
 * pristine 1024-element default regardless of what other tests ran first.
 */
class ArrayUtilsTest {

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

    // ---------------------------------------------------------------------------------------------
    // IntArray.sortedDirection
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `int empty array is ASC`() {
        assertEquals(Sorted.ASC, intArrayOf().sortedDirection())
    }

    @Test
    fun `int single element is ASC`() {
        assertEquals(Sorted.ASC, intArrayOf(42).sortedDirection())
    }

    @Test
    fun `int two equal elements is ASC`() {
        assertEquals(Sorted.ASC, intArrayOf(5, 5).sortedDirection())
    }

    @Test
    fun `int strictly ascending is ASC`() {
        assertEquals(Sorted.ASC, intArrayOf(1, 2, 3, 4, 5).sortedDirection())
    }

    @Test
    fun `int non-decreasing with duplicates is ASC`() {
        assertEquals(Sorted.ASC, intArrayOf(1, 1, 2, 2, 3).sortedDirection())
    }

    @Test
    fun `int strictly descending is DESC`() {
        assertEquals(Sorted.DESC, intArrayOf(5, 4, 3, 2, 1).sortedDirection())
    }

    @Test
    fun `int unsorted is NONE`() {
        assertEquals(Sorted.NONE, intArrayOf(1, 3, 2).sortedDirection())
    }

    @Test
    fun `int non-increasing with leading duplicate is NONE`() {
        // The DESC branch is only entered on a strict first step (a[start] > a[start+1]); a leading
        // equal pair routes into the ASC branch, which the later decrease then breaks to NONE.
        assertEquals(Sorted.NONE, intArrayOf(2, 2, 1).sortedDirection())
    }

    @Test
    fun `int descending then ascending is NONE`() {
        assertEquals(Sorted.NONE, intArrayOf(3, 1, 2).sortedDirection())
    }

    @Test
    fun `int subrange ascending is ASC`() {
        // Bytes outside [start, end) are ignored.
        assertEquals(Sorted.ASC, intArrayOf(99, 1, 2, 3, 0).sortedDirection(1, 4))
    }

    @Test
    fun `int subrange descending is DESC`() {
        assertEquals(Sorted.DESC, intArrayOf(0, 5, 4, 3, 99).sortedDirection(1, 4))
    }

    @Test
    fun `int subrange of one element is ASC`() {
        assertEquals(Sorted.ASC, intArrayOf(9, 8, 7).sortedDirection(1, 2))
    }

    @Test
    fun `int empty subrange is ASC`() {
        assertEquals(Sorted.ASC, intArrayOf(9, 8, 7).sortedDirection(1, 1))
    }

    // ---------------------------------------------------------------------------------------------
    // DoubleArray.sortedDirection
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `double empty array is ASC`() {
        assertEquals(Sorted.ASC, doubleArrayOf().sortedDirection())
    }

    @Test
    fun `double strictly ascending is ASC`() {
        assertEquals(Sorted.ASC, doubleArrayOf(1.0, 1.5, 2.0).sortedDirection())
    }

    @Test
    fun `double non-decreasing with duplicates is ASC`() {
        assertEquals(Sorted.ASC, doubleArrayOf(1.0, 1.0, 2.0).sortedDirection())
    }

    @Test
    fun `double strictly descending is DESC`() {
        assertEquals(Sorted.DESC, doubleArrayOf(3.0, 2.0, 1.0).sortedDirection())
    }

    @Test
    fun `double equal pair is ASC`() {
        assertEquals(Sorted.ASC, doubleArrayOf(7.0, 7.0).sortedDirection())
    }

    @Test
    fun `double unsorted is NONE`() {
        assertEquals(Sorted.NONE, doubleArrayOf(1.0, 3.0, 2.0).sortedDirection())
    }

    @Test
    fun `double subrange descending is DESC`() {
        assertEquals(Sorted.DESC, doubleArrayOf(0.0, 5.0, 4.0, 3.0, 9.0).sortedDirection(1, 4))
    }

    // ---------------------------------------------------------------------------------------------
    // Scratch-array factories
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `int factory returns the initial capacity buffer`() {
        val factory = ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)
        val array = onFreshThread { factory(1) }
        assertEquals(DEFAULT_CAPACITY, array.size)
    }

    @Test
    fun `int factory returns at least the requested size`() {
        val factory = ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)
        val array = onFreshThread { factory(5000) }
        assertTrue(array.size >= 5000)
    }

    @Test
    fun `int factory caches the buffer across calls that fit`() {
        val factory = ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            val a = factory(10)
            val b = factory(DEFAULT_CAPACITY)
            assertSame(a, b)
        }
    }

    @Test
    fun `int factory grows and retains the grown buffer`() {
        val factory = ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            val small = factory(10)
            val big = factory(5000)
            assertNotSame(small, big)
            assertTrue(big.size >= 5000)
            // The grown buffer is kept; a later smaller request does not shrink back.
            assertSame(big, factory(10))
        }
    }

    @Test
    fun `oversized requests are served one-shot and never cached`() {
        // Above 2MB per array the factory must hand out a fresh array each time and leave the
        // thread-local cache untouched, so one spike cannot pin memory on the thread forever.
        val overCapInts = 2 * 1024 * 1024 / Int.SIZE_BYTES + 1
        val factory = ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            val cached = factory(10)
            val huge1 = factory(overCapInts)
            val huge2 = factory(overCapInts)
            assertEquals(overCapInts, huge1.size)
            assertNotSame(huge1, huge2)
            // The cached buffer was not evicted or replaced by the oversized request.
            assertSame(cached, factory(10))
        }

        val overCapRefs = 2 * 1024 * 1024 / 4 + 1
        val objectFactory = ArrayUtils.ScratchArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            val cached = objectFactory(10)
            val huge1 = objectFactory(overCapRefs)
            val huge2 = objectFactory(overCapRefs)
            assertEquals(overCapRefs, huge1.size)
            assertNotSame(huge1, huge2)
            assertSame(cached, objectFactory(10))
        }
    }

    @Test
    fun `distinct int factories are independent`() {
        val primary = ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)
        val secondary = ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            assertNotSame(primary(10), secondary(10))
            // Growing the primary factory leaves the secondary one untouched.
            primary(5000)
            assertEquals(DEFAULT_CAPACITY, secondary(10).size)
        }
    }

    @Test
    fun `long factory grows and distinct factories are independent`() {
        val factory = ArrayUtils.ScratchLongArrayFactory(DEFAULT_CAPACITY)
        val other = ArrayUtils.ScratchLongArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            val small = factory(10)
            val big = factory(5000)
            assertNotSame(small, big)
            assertTrue(big.size >= 5000)
            assertSame(big, factory(10))
            assertEquals(DEFAULT_CAPACITY, other(10).size)
        }
    }

    @Test
    fun `double factory grows and distinct factories are independent`() {
        val factory = ArrayUtils.ScratchDoubleArrayFactory(DEFAULT_CAPACITY)
        val other = ArrayUtils.ScratchDoubleArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            val small = factory(10)
            val big = factory(5000)
            assertNotSame(small, big)
            assertTrue(big.size >= 5000)
            assertSame(big, factory(10))
            assertEquals(DEFAULT_CAPACITY, other(10).size)
        }
    }

    @Test
    fun `factories are isolated per thread`() {
        val factory = ArrayUtils.ScratchIntArrayFactory(DEFAULT_CAPACITY)
        val fromThreadA = onFreshThread { factory(10) }
        val fromThreadB = onFreshThread { factory(10) }
        assertNotSame(fromThreadA, fromThreadB)
    }

    // ---------------------------------------------------------------------------------------------
    // Remaining scratch-array factories (boolean / long-already-done / float / short / byte / char / object)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `boolean factory initial capacity, growth and retention`() {
        val f = ArrayUtils.ScratchBooleanArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            assertEquals(DEFAULT_CAPACITY, f(1).size)
            val big = f(5000)
            assertTrue(big.size >= 5000)
            assertSame(big, f(10))
        }
    }

    @Test
    fun `float factory initial capacity, growth and retention`() {
        val f = ArrayUtils.ScratchFloatArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            assertEquals(DEFAULT_CAPACITY, f(1).size)
            val big = f(5000)
            assertTrue(big.size >= 5000)
            assertSame(big, f(10))
        }
    }

    @Test
    fun `short factory initial capacity, growth and retention`() {
        val f = ArrayUtils.ScratchShortArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            assertEquals(DEFAULT_CAPACITY, f(1).size)
            val big = f(5000)
            assertTrue(big.size >= 5000)
            assertSame(big, f(10))
        }
    }

    @Test
    fun `byte factory initial capacity, growth and retention`() {
        val f = ArrayUtils.ScratchByteArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            assertEquals(DEFAULT_CAPACITY, f(1).size)
            val big = f(5000)
            assertTrue(big.size >= 5000)
            assertSame(big, f(10))
        }
    }

    @Test
    fun `char factory initial capacity, growth and retention`() {
        val f = ArrayUtils.ScratchCharArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            assertEquals(DEFAULT_CAPACITY, f(1).size)
            val big = f(5000)
            assertTrue(big.size >= 5000)
            assertSame(big, f(10))
        }
    }

    @Test
    fun `object factory initial capacity, growth, retention and storage`() {
        val f = ArrayUtils.ScratchArrayFactory(DEFAULT_CAPACITY)
        onFreshThread {
            val a = f(1)
            assertEquals(DEFAULT_CAPACITY, a.size)
            a[0] = "hello"
            assertEquals("hello", a[0])
            val big = f(5000)
            assertTrue(big.size >= 5000)
            assertSame(big, f(10))
        }
    }

    @Test
    fun `object factory requested size grows to exactly the request`() {
        val f = ArrayUtils.ScratchArrayFactory(DEFAULT_CAPACITY)
        onFreshThread { assertEquals(5000, f(5000).size) }
    }

    @Test
    fun `distinct same-type factories are independent across all element types`() {
        onFreshThread {
            val a = ArrayUtils.ScratchByteArrayFactory(DEFAULT_CAPACITY)
            val b = ArrayUtils.ScratchByteArrayFactory(DEFAULT_CAPACITY)
            assertNotSame(a(10), b(10))
            a(5000)
            assertEquals(DEFAULT_CAPACITY, b(10).size)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // warnIfShared
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `warnIfShared does not throw on a plain thread`() {
        onFreshThread { assertDoesNotThrow { ArrayUtils.warnIfShared() } }
    }

    @Test
    fun `warnIfShared does not throw on a ForkJoinWorkerThread`() {
        // On an FJ worker it takes the silent (no-warn) branch; either way it must not throw.
        ForkJoinPool(1).submit { ArrayUtils.warnIfShared() }.get()
    }

    // ---------------------------------------------------------------------------------------------
    // drain (object Array<Any?> + every primitive overload)
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `object drain returns the first n as a typed list`() {
        val a = arrayOf<Any?>("a", "b", "c", "d")
        assertEquals(listOf("a", "b"), a.drain<String>(2))
    }

    @Test
    fun `object drain nulls the consumed prefix and leaves the tail`() {
        val a = arrayOf<Any?>("a", "b", "c", "d")
        a.drain<String>(2)
        assertNull(a[0]); assertNull(a[1])
        assertEquals("c", a[2]); assertEquals("d", a[3])
    }

    @Test
    fun `object drain of zero returns empty and mutates nothing`() {
        val a = arrayOf<Any?>("a", "b")
        assertTrue(a.drain<String>(0).isEmpty())
        assertEquals("a", a[0]); assertEquals("b", a[1])
    }

    @Test
    fun `object drain of full size returns all and nulls all`() {
        val a = arrayOf<Any?>("a", "b", "c")
        assertEquals(listOf("a", "b", "c"), a.drain<String>(3))
        assertTrue(a.all { it == null })
    }

    @Test
    fun `object drain result is independent of subsequent array mutation`() {
        val a = arrayOf<Any?>("a", "b", "c")
        val drained = a.drain<String>(2)
        a[2] = "z"
        assertEquals(listOf("a", "b"), drained)
    }

    @Test
    fun `object drain past size throws`() {
        val a = arrayOf<Any?>("a", "b")
        assertThrows(IndexOutOfBoundsException::class.java) { a.drain<String>(3) }
    }

    @Test
    fun `long drain returns first n and does not mutate source`() {
        val a = longArrayOf(10, 20, 30)
        assertEquals(listOf(10L, 20L), a.drain(2))
        assertEquals(10L, a[0]); assertEquals(20L, a[1]); assertEquals(30L, a[2])
    }

    @Test
    fun `int drain returns first n`() {
        assertEquals(listOf(1, 2, 3), intArrayOf(1, 2, 3, 4).drain(3))
    }

    @Test
    fun `double drain returns first n`() {
        assertEquals(listOf(1.5, 2.5), doubleArrayOf(1.5, 2.5, 3.5).drain(2))
    }

    @Test
    fun `float drain returns first n`() {
        assertEquals(listOf(1.0f, 2.0f), floatArrayOf(1.0f, 2.0f, 3.0f).drain(2))
    }

    @Test
    fun `short drain returns first n`() {
        assertEquals(listOf<Short>(1, 2), shortArrayOf(1, 2, 3).drain(2))
    }

    @Test
    fun `byte drain returns first n`() {
        assertEquals(listOf<Byte>(1, 2), byteArrayOf(1, 2, 3).drain(2))
    }

    @Test
    fun `char drain returns first n`() {
        assertEquals(listOf('a', 'b'), charArrayOf('a', 'b', 'c').drain(2))
    }

    @Test
    fun `boolean drain returns first n`() {
        assertEquals(listOf(true, false), booleanArrayOf(true, false, true).drain(2))
    }

    @Test
    fun `primitive drain of zero is empty`() {
        assertTrue(intArrayOf(1, 2, 3).drain(0).isEmpty())
        assertTrue(longArrayOf(1, 2).drain(0).isEmpty())
    }

    @Test
    fun `primitive drain of full size returns the whole prefix`() {
        assertEquals(listOf(1, 2, 3), intArrayOf(1, 2, 3).drain(3))
    }

    // ---------------------------------------------------------------------------------------------
    // clear
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `clear nulls the first n and leaves the tail`() {
        val a = arrayOf<Any?>("a", "b", "c", "d")
        a.clear(2)
        assertNull(a[0]); assertNull(a[1])
        assertEquals("c", a[2]); assertEquals("d", a[3])
    }

    @Test
    fun `clear of zero mutates nothing`() {
        val a = arrayOf<Any?>("a", "b")
        a.clear(0)
        assertEquals("a", a[0]); assertEquals("b", a[1])
    }

    @Test
    fun `clear of full size nulls everything`() {
        val a = arrayOf<Any?>("a", "b", "c")
        a.clear(3)
        assertTrue(a.all { it == null })
    }
}
