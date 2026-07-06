package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * Tests for [EntangledArrayUtils]: a global `object` exposing six shared, thread-local scratch-array
 * factories (INT, INT_2, INT_3, LONG, DOUBLE, DOUBLE_2), each constructed with INITIAL_SIZE = 1024.
 *
 * These factories are GLOBAL singletons also used by production EntangledArray serializers/combinators
 * and possibly by other concurrently-running tests. Each factory caches one buffer per thread, growing
 * but never shrinking, so its behavior is only deterministic against a thread whose [ThreadLocal] has
 * not yet touched that factory. Every assertion that depends on the pristine 1024-element default
 * therefore runs on a brand-new [Thread] via [onFreshThread]. We never assert an exact length of
 * exactly 1024 against a buffer that may already have grown in a shared thread; we assert
 * `length >= requested` and `length >= INITIAL_SIZE`, and only assert an exact 1024 on a fresh thread.
 */
class EntangledArrayUtilsTest {

    private val INITIAL_SIZE = 1024

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

    // =============================================================================================
    // 1. Non-null factory properties
    // =============================================================================================

    @Test
    fun `INT factory is non-null`() = assertNotNull(EntangledArrayUtils.INT)

    @Test
    fun `INT_2 factory is non-null`() = assertNotNull(EntangledArrayUtils.INT_2)

    @Test
    fun `INT_3 factory is non-null`() = assertNotNull(EntangledArrayUtils.INT_3)

    @Test
    fun `LONG factory is non-null`() = assertNotNull(EntangledArrayUtils.LONG)

    @Test
    fun `DOUBLE factory is non-null`() = assertNotNull(EntangledArrayUtils.DOUBLE)

    @Test
    fun `DOUBLE_2 factory is non-null`() = assertNotNull(EntangledArrayUtils.DOUBLE_2)

    @Test
    fun `factory properties are stable singletons across reads`() {
        assertSame(EntangledArrayUtils.INT, EntangledArrayUtils.INT)
        assertSame(EntangledArrayUtils.LONG, EntangledArrayUtils.LONG)
        assertSame(EntangledArrayUtils.DOUBLE, EntangledArrayUtils.DOUBLE)
    }

    // =============================================================================================
    // 2. Element types
    // =============================================================================================

    // The factory's invoke() return type is statically the primitive array, so we assert the runtime
    // class to document/verify the element type rather than a (statically-true) `is` check.
    @Test
    fun `INT factory yields an IntArray`() {
        assertEquals(IntArray::class.java, onFreshThread { EntangledArrayUtils.INT(1) }.javaClass)
    }

    @Test
    fun `INT_2 factory yields an IntArray`() {
        assertEquals(IntArray::class.java, onFreshThread { EntangledArrayUtils.INT_2(1) }.javaClass)
    }

    @Test
    fun `INT_3 factory yields an IntArray`() {
        assertEquals(IntArray::class.java, onFreshThread { EntangledArrayUtils.INT_3(1) }.javaClass)
    }

    @Test
    fun `LONG factory yields a LongArray`() {
        assertEquals(LongArray::class.java, onFreshThread { EntangledArrayUtils.LONG(1) }.javaClass)
    }

    @Test
    fun `DOUBLE factory yields a DoubleArray`() {
        assertEquals(DoubleArray::class.java, onFreshThread { EntangledArrayUtils.DOUBLE(1) }.javaClass)
    }

    @Test
    fun `DOUBLE_2 factory yields a DoubleArray`() {
        assertEquals(DoubleArray::class.java, onFreshThread { EntangledArrayUtils.DOUBLE_2(1) }.javaClass)
    }

    // =============================================================================================
    // 3. Initial capacity (fresh thread) — length is exactly INITIAL_SIZE for a small request
    // =============================================================================================

    @Test
    fun `INT initial buffer is INITIAL_SIZE on a fresh thread`() {
        assertEquals(INITIAL_SIZE, onFreshThread { EntangledArrayUtils.INT(1).size })
    }

    @Test
    fun `INT_2 initial buffer is INITIAL_SIZE on a fresh thread`() {
        assertEquals(INITIAL_SIZE, onFreshThread { EntangledArrayUtils.INT_2(1).size })
    }

    @Test
    fun `INT_3 initial buffer is INITIAL_SIZE on a fresh thread`() {
        assertEquals(INITIAL_SIZE, onFreshThread { EntangledArrayUtils.INT_3(1).size })
    }

    @Test
    fun `LONG initial buffer is INITIAL_SIZE on a fresh thread`() {
        assertEquals(INITIAL_SIZE, onFreshThread { EntangledArrayUtils.LONG(1).size })
    }

    @Test
    fun `DOUBLE initial buffer is INITIAL_SIZE on a fresh thread`() {
        assertEquals(INITIAL_SIZE, onFreshThread { EntangledArrayUtils.DOUBLE(1).size })
    }

    @Test
    fun `DOUBLE_2 initial buffer is INITIAL_SIZE on a fresh thread`() {
        assertEquals(INITIAL_SIZE, onFreshThread { EntangledArrayUtils.DOUBLE_2(1).size })
    }

    @Test
    fun `request equal to INITIAL_SIZE keeps the initial buffer on a fresh thread`() {
        assertEquals(INITIAL_SIZE, onFreshThread { EntangledArrayUtils.INT(INITIAL_SIZE).size })
    }

    // =============================================================================================
    // 4. Length contract for shared threads — always >= requested and >= INITIAL_SIZE
    // =============================================================================================

    @Test
    fun `INT returns at least requested and at least INITIAL_SIZE on shared thread`() {
        val a = EntangledArrayUtils.INT(7)
        assertTrue(a.size >= 7)
        assertTrue(a.size >= INITIAL_SIZE)
    }

    @Test
    fun `LONG returns at least requested and at least INITIAL_SIZE on shared thread`() {
        val a = EntangledArrayUtils.LONG(7)
        assertTrue(a.size >= 7)
        assertTrue(a.size >= INITIAL_SIZE)
    }

    @Test
    fun `DOUBLE returns at least requested and at least INITIAL_SIZE on shared thread`() {
        val a = EntangledArrayUtils.DOUBLE(7)
        assertTrue(a.size >= 7)
        assertTrue(a.size >= INITIAL_SIZE)
    }

    // =============================================================================================
    // 5. Growth + retention (fresh thread)
    // =============================================================================================

    @Test
    fun `INT grows to at least the request and retains the grown buffer`() {
        onFreshThread {
            val small = EntangledArrayUtils.INT(10)
            val big = EntangledArrayUtils.INT(5000)
            assertNotSame(small, big)
            assertTrue(big.size >= 5000)
            assertSame(big, EntangledArrayUtils.INT(10)) // does not shrink back
        }
    }

    @Test
    fun `INT_2 grows to at least the request and retains the grown buffer`() {
        onFreshThread {
            val small = EntangledArrayUtils.INT_2(10)
            val big = EntangledArrayUtils.INT_2(5000)
            assertNotSame(small, big)
            assertTrue(big.size >= 5000)
            assertSame(big, EntangledArrayUtils.INT_2(10))
        }
    }

    @Test
    fun `INT_3 grows to at least the request and retains the grown buffer`() {
        onFreshThread {
            val small = EntangledArrayUtils.INT_3(10)
            val big = EntangledArrayUtils.INT_3(5000)
            assertNotSame(small, big)
            assertTrue(big.size >= 5000)
            assertSame(big, EntangledArrayUtils.INT_3(10))
        }
    }

    @Test
    fun `LONG grows to at least the request and retains the grown buffer`() {
        onFreshThread {
            val small = EntangledArrayUtils.LONG(10)
            val big = EntangledArrayUtils.LONG(5000)
            assertNotSame(small, big)
            assertTrue(big.size >= 5000)
            assertSame(big, EntangledArrayUtils.LONG(10))
        }
    }

    @Test
    fun `DOUBLE grows to at least the request and retains the grown buffer`() {
        onFreshThread {
            val small = EntangledArrayUtils.DOUBLE(10)
            val big = EntangledArrayUtils.DOUBLE(5000)
            assertNotSame(small, big)
            assertTrue(big.size >= 5000)
            assertSame(big, EntangledArrayUtils.DOUBLE(10))
        }
    }

    @Test
    fun `DOUBLE_2 grows to at least the request and retains the grown buffer`() {
        onFreshThread {
            val small = EntangledArrayUtils.DOUBLE_2(10)
            val big = EntangledArrayUtils.DOUBLE_2(5000)
            assertNotSame(small, big)
            assertTrue(big.size >= 5000)
            assertSame(big, EntangledArrayUtils.DOUBLE_2(10))
        }
    }

    @Test
    fun `INT grows to exactly the requested size on a fresh thread`() {
        // The factory allocates exactly minSize when growing (see ScratchIntArrayFactory.invoke).
        assertEquals(5000, onFreshThread { EntangledArrayUtils.INT(5000).size })
    }

    @Test
    fun `LONG grows to exactly the requested size on a fresh thread`() {
        assertEquals(5000, onFreshThread { EntangledArrayUtils.LONG(5000).size })
    }

    @Test
    fun `DOUBLE grows to exactly the requested size on a fresh thread`() {
        assertEquals(5000, onFreshThread { EntangledArrayUtils.DOUBLE(5000).size })
    }

    @Test
    fun `INT reuses the same buffer for repeated within-capacity calls on a fresh thread`() {
        onFreshThread {
            val a = EntangledArrayUtils.INT(10)
            val b = EntangledArrayUtils.INT(INITIAL_SIZE)
            val c = EntangledArrayUtils.INT(1)
            assertSame(a, b)
            assertSame(b, c)
        }
    }

    // =============================================================================================
    // 6. Distinctness — INT / INT_2 / INT_3 independent; DOUBLE / DOUBLE_2 independent
    // =============================================================================================

    @Test
    fun `INT INT_2 INT_3 hand out distinct buffer instances`() {
        onFreshThread {
            val a = EntangledArrayUtils.INT(10)
            val b = EntangledArrayUtils.INT_2(10)
            val c = EntangledArrayUtils.INT_3(10)
            assertNotSame(a, b)
            assertNotSame(b, c)
            assertNotSame(a, c)
        }
    }

    @Test
    fun `growing INT does not affect INT_2 or INT_3`() {
        onFreshThread {
            EntangledArrayUtils.INT(5000)
            assertEquals(INITIAL_SIZE, EntangledArrayUtils.INT_2(10).size)
            assertEquals(INITIAL_SIZE, EntangledArrayUtils.INT_3(10).size)
        }
    }

    @Test
    fun `growing INT_2 does not affect INT or INT_3`() {
        onFreshThread {
            EntangledArrayUtils.INT_2(5000)
            assertEquals(INITIAL_SIZE, EntangledArrayUtils.INT(10).size)
            assertEquals(INITIAL_SIZE, EntangledArrayUtils.INT_3(10).size)
        }
    }

    @Test
    fun `growing INT_3 does not affect INT or INT_2`() {
        onFreshThread {
            EntangledArrayUtils.INT_3(5000)
            assertEquals(INITIAL_SIZE, EntangledArrayUtils.INT(10).size)
            assertEquals(INITIAL_SIZE, EntangledArrayUtils.INT_2(10).size)
        }
    }

    @Test
    fun `DOUBLE and DOUBLE_2 hand out distinct buffer instances`() {
        onFreshThread {
            assertNotSame(EntangledArrayUtils.DOUBLE(10), EntangledArrayUtils.DOUBLE_2(10))
        }
    }

    @Test
    fun `growing DOUBLE does not affect DOUBLE_2`() {
        onFreshThread {
            EntangledArrayUtils.DOUBLE(5000)
            assertEquals(INITIAL_SIZE, EntangledArrayUtils.DOUBLE_2(10).size)
        }
    }

    @Test
    fun `growing DOUBLE_2 does not affect DOUBLE`() {
        onFreshThread {
            EntangledArrayUtils.DOUBLE_2(5000)
            assertEquals(INITIAL_SIZE, EntangledArrayUtils.DOUBLE(10).size)
        }
    }

    @Test
    fun `the six factory properties are six distinct factory instances`() {
        val factories = listOf(
            EntangledArrayUtils.INT, EntangledArrayUtils.INT_2, EntangledArrayUtils.INT_3,
            EntangledArrayUtils.LONG, EntangledArrayUtils.DOUBLE, EntangledArrayUtils.DOUBLE_2,
        )
        // System.identityHashCode collisions are possible but distinct instances must be referentially
        // unequal pairwise.
        for (i in factories.indices)
            for (j in i + 1 until factories.size)
                assertNotSame(factories[i], factories[j], "factory $i and $j must be distinct")
    }

    @Test
    fun `INT family and LONG family are distinct types`() {
        onFreshThread {
            val i = EntangledArrayUtils.INT(10)
            val l = EntangledArrayUtils.LONG(10)
            assertEquals(IntArray::class.java, i.javaClass)
            assertEquals(LongArray::class.java, l.javaClass)
            // distinct underlying instances trivially (different array classes)
            assertFalse((i as Any) === (l as Any))
        }
    }

    @Test
    fun `writes to INT do not leak into INT_2`() {
        onFreshThread {
            val a = EntangledArrayUtils.INT(16)
            val b = EntangledArrayUtils.INT_2(16)
            for (k in 0 until 16) a[k] = 111
            for (k in 0 until 16) assertEquals(0, b[k]) // b is a fresh, distinct, zero-filled buffer
        }
    }

    // =============================================================================================
    // 7. Thread-locality
    // =============================================================================================

    @Test
    fun `INT gives different buffers to different threads`() {
        val fromA = onFreshThread { EntangledArrayUtils.INT(10) }
        val fromB = onFreshThread { EntangledArrayUtils.INT(10) }
        assertNotSame(fromA, fromB)
    }

    @Test
    fun `LONG gives different buffers to different threads`() {
        val fromA = onFreshThread { EntangledArrayUtils.LONG(10) }
        val fromB = onFreshThread { EntangledArrayUtils.LONG(10) }
        assertNotSame(fromA, fromB)
    }

    @Test
    fun `DOUBLE gives different buffers to different threads`() {
        val fromA = onFreshThread { EntangledArrayUtils.DOUBLE(10) }
        val fromB = onFreshThread { EntangledArrayUtils.DOUBLE(10) }
        assertNotSame(fromA, fromB)
    }

    @Test
    fun `same thread reusing INT within capacity gets the same instance`() {
        onFreshThread {
            val first = EntangledArrayUtils.INT(10)
            val second = EntangledArrayUtils.INT(10)
            assertSame(first, second)
        }
    }

    @Test
    fun `growth on one thread does not affect another thread's buffer`() {
        // Thread A grows its INT buffer to 5000; thread B (fresh) must still start at INITIAL_SIZE.
        onFreshThread { EntangledArrayUtils.INT(5000) }
        assertEquals(INITIAL_SIZE, onFreshThread { EntangledArrayUtils.INT(10).size })
    }

    @Test
    fun `two concurrent threads obtain isolated INT buffers`() {
        val results = arrayOfNulls<IntArray>(2)
        val gate = java.util.concurrent.CountDownLatch(1)
        val t0 = Thread {
            gate.await()
            val buf = EntangledArrayUtils.INT(16)
            for (k in 0 until 16) buf[k] = 1
            results[0] = buf
        }
        val t1 = Thread {
            gate.await()
            val buf = EntangledArrayUtils.INT(16)
            for (k in 0 until 16) buf[k] = 2
            results[1] = buf
        }
        t0.start(); t1.start()
        gate.countDown()
        t0.join(); t1.join()
        assertNotSame(results[0], results[1])
        // No cross-contamination: each thread sees only its own writes.
        assertTrue(results[0]!!.take(16).all { it == 1 })
        assertTrue(results[1]!!.take(16).all { it == 2 })
    }

    // =============================================================================================
    // 8. INITIAL_SIZE via reflection
    // =============================================================================================

    @Test
    fun `INITIAL_SIZE constant equals 1024 via reflection`() {
        val field = EntangledArrayUtils::class.java.getDeclaredField("INITIAL_SIZE").apply { isAccessible = true }
        assertEquals(1024, field.getInt(EntangledArrayUtils))
    }

    @Test
    fun `every factory's fresh buffer length matches the reflected INITIAL_SIZE`() {
        val field = EntangledArrayUtils::class.java.getDeclaredField("INITIAL_SIZE").apply { isAccessible = true }
        val expected = field.getInt(EntangledArrayUtils)
        assertEquals(expected, onFreshThread { EntangledArrayUtils.INT(1).size })
        assertEquals(expected, onFreshThread { EntangledArrayUtils.INT_2(1).size })
        assertEquals(expected, onFreshThread { EntangledArrayUtils.INT_3(1).size })
        assertEquals(expected, onFreshThread { EntangledArrayUtils.LONG(1).size })
        assertEquals(expected, onFreshThread { EntangledArrayUtils.DOUBLE(1).size })
        assertEquals(expected, onFreshThread { EntangledArrayUtils.DOUBLE_2(1).size })
    }

    // =============================================================================================
    // 9. Integration — realistic parallel level/quantity buffer usage
    // =============================================================================================

    @Test
    fun `INT and INT_2 used together stay independent across many iterations`() {
        onFreshThread {
            repeat(1000) { iter ->
                val n = 64
                val levels = EntangledArrayUtils.INT(n)
                val quantities = EntangledArrayUtils.INT_2(n)
                assertNotSame(levels, quantities)
                for (k in 0 until n) {
                    levels[k] = iter * 1000 + k
                    quantities[k] = -(iter * 1000 + k)
                }
                for (k in 0 until n) {
                    assertEquals(iter * 1000 + k, levels[k])
                    assertEquals(-(iter * 1000 + k), quantities[k])
                }
            }
        }
    }

    @Test
    fun `INT LONG DOUBLE used together as parallel typed buffers stay independent`() {
        onFreshThread {
            repeat(500) { iter ->
                val n = 32
                val ints = EntangledArrayUtils.INT(n)
                val longs = EntangledArrayUtils.LONG(n)
                val doubles = EntangledArrayUtils.DOUBLE(n)
                for (k in 0 until n) {
                    ints[k] = iter + k
                    longs[k] = (iter + k).toLong() * 1_000_000_000L
                    doubles[k] = (iter + k) + 0.5
                }
                for (k in 0 until n) {
                    assertEquals(iter + k, ints[k])
                    assertEquals((iter + k).toLong() * 1_000_000_000L, longs[k])
                    assertEquals((iter + k) + 0.5, doubles[k])
                }
            }
        }
    }

    @Test
    fun `buffers remain usable after a grow-then-shrink request sequence`() {
        onFreshThread {
            EntangledArrayUtils.INT(10)
            val big = EntangledArrayUtils.INT(4096)
            // smaller request returns the same (grown) buffer; still fully writable
            val again = EntangledArrayUtils.INT(8)
            assertSame(big, again)
            for (k in again.indices) again[k] = k
            assertEquals(again.size - 1, again[again.size - 1])
        }
    }

    // =============================================================================================
    // 10. Performance + memory — repeated within-capacity invoke() allocates ~zero bytes
    // =============================================================================================

    private fun threadBean(): ThreadMXBean = ManagementFactory.getThreadMXBean() as ThreadMXBean

    @Test
    fun `repeated INT invoke within capacity allocates near zero versus fresh IntArray`() {
        val bean = threadBean()
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        onFreshThread {
            val tid = Thread.currentThread().threadId()
            val iterations = 2_000_000

            // warmup both paths
            repeat(200_000) { EntangledArrayUtils.INT(256)[0] = it }
            var sink = 0
            repeat(200_000) { sink += IntArray(256).size }

            val reuseBefore = bean.getThreadAllocatedBytes(tid)
            for (i in 0 until iterations) EntangledArrayUtils.INT(256)[0] = i
            val reuseBytes = (bean.getThreadAllocatedBytes(tid) - reuseBefore).toDouble() / iterations

            val freshBefore = bean.getThreadAllocatedBytes(tid)
            for (i in 0 until iterations) sink += IntArray(256).size
            val freshBytes = (bean.getThreadAllocatedBytes(tid) - freshBefore).toDouble() / iterations

            assertTrue(sink != Int.MIN_VALUE) // keep sink live
            println("INT reuse bytes/op = $reuseBytes ; fresh IntArray(256) bytes/op = $freshBytes")
            // Reuse must allocate essentially nothing per op (a small constant, far below a fresh array).
            assertTrue(reuseBytes < 8.0, "scratch reuse should allocate ~0 bytes/op, was $reuseBytes")
            assertTrue(reuseBytes < freshBytes * 0.1,
                "scratch reuse should allocate <10% of a fresh array: reuse=$reuseBytes fresh=$freshBytes")
        }
    }

    @Test
    fun `repeated LONG invoke within capacity allocates near zero`() {
        val bean = threadBean()
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        onFreshThread {
            val tid = Thread.currentThread().threadId()
            val iterations = 2_000_000
            repeat(200_000) { EntangledArrayUtils.LONG(256)[0] = it.toLong() }
            val before = bean.getThreadAllocatedBytes(tid)
            for (i in 0 until iterations) EntangledArrayUtils.LONG(256)[0] = i.toLong()
            val bytes = (bean.getThreadAllocatedBytes(tid) - before).toDouble() / iterations
            println("LONG reuse bytes/op = $bytes")
            assertTrue(bytes < 8.0, "scratch reuse should allocate ~0 bytes/op, was $bytes")
        }
    }

    @Test
    fun `repeated DOUBLE invoke within capacity allocates near zero`() {
        val bean = threadBean()
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        onFreshThread {
            val tid = Thread.currentThread().threadId()
            val iterations = 2_000_000
            repeat(200_000) { EntangledArrayUtils.DOUBLE(256)[0] = it.toDouble() }
            val before = bean.getThreadAllocatedBytes(tid)
            for (i in 0 until iterations) EntangledArrayUtils.DOUBLE(256)[0] = i.toDouble()
            val bytes = (bean.getThreadAllocatedBytes(tid) - before).toDouble() / iterations
            println("DOUBLE reuse bytes/op = $bytes")
            assertTrue(bytes < 8.0, "scratch reuse should allocate ~0 bytes/op, was $bytes")
        }
    }

    @Test
    fun `combined INT plus INT_2 reuse allocates near zero per op`() {
        val bean = threadBean()
        assumeTrue(bean.isThreadAllocatedMemorySupported)
        onFreshThread {
            val tid = Thread.currentThread().threadId()
            val iterations = 1_000_000
            repeat(100_000) {
                EntangledArrayUtils.INT(128)[0] = it
                EntangledArrayUtils.INT_2(128)[0] = it
            }
            val before = bean.getThreadAllocatedBytes(tid)
            for (i in 0 until iterations) {
                EntangledArrayUtils.INT(128)[0] = i
                EntangledArrayUtils.INT_2(128)[0] = i
            }
            val bytes = (bean.getThreadAllocatedBytes(tid) - before).toDouble() / iterations
            println("INT+INT_2 reuse bytes/op = $bytes")
            assertTrue(bytes < 16.0, "parallel scratch reuse should allocate ~0 bytes/op, was $bytes")
        }
    }
}
