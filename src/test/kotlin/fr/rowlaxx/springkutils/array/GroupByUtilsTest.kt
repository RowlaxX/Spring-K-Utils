package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import fr.rowlaxx.springkutils.array.GroupByUtils.GroupedBy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

class GroupByUtilsTest {

    private data class E(val key: Long, val id: Int)

    private fun e(key: Long, id: Int) = E(key, id)

    private val grouper = GroupByUtils.grouper<E>()

    private inline fun <R> List<E>.grouped(noinline key: (E) -> Long = { it.key }, block: (GroupedBy<E>) -> R): R =
        grouper.groupBy(this, key).use(block)

    private fun List<E>.snapshot(key: (E) -> Long = { it.key }): List<Pair<Long, List<Int>>> =
        grouped(key) { it.snapshot() }

    private fun GroupedBy<E>.snapshot(): List<Pair<Long, List<Int>>> {
        val out = ArrayList<Pair<Long, List<Int>>>()
        forEach { key, list -> out.add(key to list.map { it.id }) }
        return out
    }

    private fun <T> onFreshThread(block: () -> T): T {
        var result: Result<T>? = null
        val thread = Thread { result = runCatching(block) }
        thread.start()
        thread.join()
        return result!!.getOrThrow()
    }

    // -- degenerate sizes ---------------------------------------------------------------------------

    @Test
    fun `empty input yields an empty grouping`() {
        emptyList<E>().grouped { g ->
            assertEquals(0, g.count)
            assertTrue(g.first().isEmpty())
            assertTrue(g.last().isEmpty())
            assertFalse(g.contains(0L))
            assertTrue(g[0L].isEmpty())
            var visited = 0
            g.forEach { _, _ -> visited++ }
            g.forEachKey { visited++ }
            assertEquals(0, visited)
            assertThrows(NoSuchElementException::class.java) { g.firstKey }
            assertThrows(NoSuchElementException::class.java) { g.lastKey }
        }
    }

    @Test
    fun `single element grouping`() {
        listOf(e(42L, 1)).grouped { g ->
            assertEquals(1, g.count)
            assertTrue(g.contains(42L))
            assertEquals(listOf(1), g[42L].map { it.id })
            assertEquals(listOf(1), g.first().map { it.id })
            assertEquals(listOf(1), g.last().map { it.id })
            assertEquals(42L, g.firstKey)
            assertEquals(42L, g.lastKey)
        }
    }

    @Test
    fun `all elements share one key`() {
        assertEquals(listOf(7L to listOf(0, 1, 2, 3, 4)), (0 until 5).map { e(7L, it) }.snapshot())
        assertEquals(listOf(0L to (0 until 100).toList()), (0 until 100).map { e(it.toLong(), it) }.snapshot { 0L })
    }

    // -- sorted path --------------------------------------------------------------------------------

    @Test
    fun `already sorted input groups in one linear pass`() {
        val items = listOf(e(1, 0), e(1, 1), e(4, 2), e(9, 3), e(9, 4), e(9, 5))
        assertEquals(listOf(1L to listOf(0, 1), 4L to listOf(2), 9L to listOf(3, 4, 5)), items.snapshot())
    }

    // -- dense path (span <= n) ---------------------------------------------------------------------

    @Test
    fun `dense path groups by contiguous keys`() {
        val items = listOf(e(2, 0), e(0, 1), e(1, 2), e(0, 3), e(2, 4), e(1, 5))
        assertEquals(listOf(0L to listOf(1, 3), 1L to listOf(2, 5), 2L to listOf(0, 4)), items.snapshot())
    }

    @Test
    fun `dense path is stable within a group`() {
        listOf(e(1, 10), e(1, 11), e(0, 12), e(1, 13)).grouped { g ->
            assertEquals(listOf(12), g[0L].map { it.id })
            assertEquals(listOf(10, 11, 13), g[1L].map { it.id })
        }
    }

    @Test
    fun `dense path with span exactly n`() {
        assertEquals(listOf(0L to listOf(0), 1L to listOf(1)), listOf(e(1, 1), e(0, 0)).snapshot())
    }

    @Test
    fun `dense path skips empty intermediate buckets`() {
        val items = listOf(e(0, 0), e(4, 1), e(0, 2), e(4, 3), e(0, 4), e(4, 5))
        assertEquals(listOf(0L to listOf(0, 2, 4), 4L to listOf(1, 3, 5)), items.snapshot())
    }

    @Test
    fun `dense path with negative keys`() {
        val items = listOf(e(-2, 0), e(-1, 1), e(-2, 2), e(0, 3), e(-5, 4))
        assertEquals(
            listOf(-5L to listOf(4), -2L to listOf(0, 2), -1L to listOf(1), 0L to listOf(3)),
            items.snapshot(),
        )
    }

    @Test
    fun `dense path takes a small contiguous span`() {
        listOf(e(10L, 0), e(11L, 1), e(10L, 2), e(12L, 3)).grouped { g ->
            assertEquals(3, g.count)
            assertEquals(listOf(0, 2), g[10L].map { it.id })
            assertEquals(listOf(1), g[11L].map { it.id })
            assertEquals(listOf(3), g[12L].map { it.id })
        }
    }

    // -- sparse path (span > n) ---------------------------------------------------------------------

    @Test
    fun `sparse path groups widely separated keys`() {
        val items = listOf(e(1000, 0), e(5, 1), e(7, 2), e(1000, 3), e(5, 4))
        assertEquals(listOf(5L to listOf(1, 4), 7L to listOf(2), 1000L to listOf(0, 3)), items.snapshot())
    }

    @Test
    fun `sparse path is stable for many duplicates of two far keys`() {
        (0 until 20).map { e(if (it % 2 == 0) 5_000_000L else 0L, it) }.grouped { g ->
            assertEquals(2, g.count)
            assertEquals((1 until 20 step 2).toList(), g[0L].map { it.id })
            assertEquals((0 until 20 step 2).toList(), g[5_000_000L].map { it.id })
        }
    }

    @Test
    fun `sparse path sorts descending input ascending`() {
        (0 until 10).map { e(10_000L * (10 - it), it) }.grouped { g ->
            val keys = buildList { g.forEachKey { add(it) } }
            assertEquals((1L..10L).map { it * 10_000L }, keys)
        }
    }

    @Test
    fun `keys whose span overflows Long take the sparse path`() {
        listOf(e(Long.MIN_VALUE, 0), e(5L, 1), e(Long.MAX_VALUE, 2), e(5L, 3)).grouped { g ->
            assertEquals(3, g.count)
            assertEquals(listOf(0), g[Long.MIN_VALUE].map { it.id })
            assertEquals(listOf(1, 3), g[5L].map { it.id })
            assertEquals(listOf(2), g[Long.MAX_VALUE].map { it.id })
            assertTrue(g[12_345L].isEmpty())
        }
    }

    @Test
    fun `every path groups every element exactly once`() {
        val shapes = listOf(
            (0 until 1000).map { e((it % 50).toLong(), it) },              // dense
            (0 until 500).map { e((it % 25) * 1_000_000L, it) },           // sparse
            (0 until 1000).map { e((it / 7).toLong(), it) },               // sorted
        )
        for (items in shapes) {
            val expected = items.groupBy { it.key }.toSortedMap().map { (k, v) -> k to v.map { it.id } }
            assertEquals(expected, items.snapshot())
        }
    }

    // -- accessors ----------------------------------------------------------------------------------

    @Test
    fun `accessors on a multi key grouping`() {
        listOf(e(3, 30), e(1, 10), e(2, 20), e(3, 31), e(1, 11)).grouped { g ->
            assertEquals(3, g.count)
            assertEquals(listOf(10, 11), g.first().map { it.id })
            assertEquals(listOf(30, 31), g.last().map { it.id })
            assertEquals(1L, g.firstKey)
            assertEquals(3L, g.lastKey)
            assertEquals(2L, g.keyAt(1))
            assertEquals(listOf(20), g.groupAt(1).map { it.id })
            assertTrue(g.contains(2L))
            assertFalse(g.contains(4L))
            assertTrue(g[Long.MIN_VALUE].isEmpty())
            assertThrows(IndexOutOfBoundsException::class.java) { g.keyAt(3) }
            assertThrows(IndexOutOfBoundsException::class.java) { g.groupAt(-1) }
        }
    }

    @Test
    fun `group list is bounds checked and iterable`() {
        listOf(e(1, 7), e(1, 8), e(1, 9)).grouped { g ->
            val list = g[1L]
            assertEquals(3, list.size)
            assertEquals(listOf(7, 8, 9), list.map { it.id })
            assertThrows(IndexOutOfBoundsException::class.java) { list[3] }
            assertThrows(IndexOutOfBoundsException::class.java) { list[-1] }
        }
    }

    @Test
    fun `key function derives the grouping key`() {
        val items = listOf(e(11, 0), e(13, 1), e(25, 2), e(29, 3))
        assertEquals(listOf(1L to listOf(0, 1), 2L to listOf(2, 3)), items.snapshot { it.key / 10 })
    }

    // -- array overload -----------------------------------------------------------------------------

    @Test
    fun `array overload groups only the requested window`() {
        val array = arrayOf<Any?>(e(9, 0), e(2, 1), e(1, 2), e(2, 3), e(9, 4))
        grouper.groupBy(array, 1, 4) { it.key }.use { g ->
            assertEquals(listOf(1L to listOf(2), 2L to listOf(1, 3)), g.snapshot())
        }
        grouper.groupBy(array, 2, 2) { it.key }.use { assertEquals(0, it.count) }
        assertThrows(IndexOutOfBoundsException::class.java) { grouper.groupBy(array, 3, 6) { it.key } }
        assertThrows(IndexOutOfBoundsException::class.java) { grouper.groupBy(array, 3, 2) { it.key } }
    }

    // -- safety -------------------------------------------------------------------------------------

    @Test
    fun `a second grouping on the same thread is rejected while one is open`() {
        listOf(e(2, 0), e(1, 1), e(2, 2)).grouped { g ->
            assertThrows(IllegalStateException::class.java) { grouper.groupBy(listOf(e(7, 10)), E::key) }
            assertThrows(IllegalStateException::class.java) {
                grouper.groupBy(arrayOf<Any?>(e(7, 10)), 0, 1, E::key)
            }
            assertEquals(listOf(1L to listOf(1), 2L to listOf(0, 2)), g.snapshot())
        }
        assertEquals(listOf(7L to listOf(10)), listOf(e(7, 10)).snapshot())
    }

    @Test
    fun `another thread can group while this one holds a grouping`() {
        listOf(e(1, 0), e(2, 1)).grouped { g ->
            assertEquals(listOf(5L to listOf(9)), onFreshThread { listOf(e(5, 9)).snapshot() })
            assertEquals(listOf(1L to listOf(0), 2L to listOf(1)), g.snapshot())
        }
    }

    @Test
    fun `grouping again from inside forEach of the same grouper is rejected`() {
        listOf(e(1, 0), e(2, 1)).grouped { g ->
            var visited = 0
            g.forEach { _, _ ->
                assertThrows(IllegalStateException::class.java) { grouper.groupBy(listOf(e(9, 9)), E::key) }
                visited++
            }
            assertEquals(2, visited)
            assertEquals(listOf(1L to listOf(0), 2L to listOf(1)), g.snapshot())
        }
    }

    @Test
    fun `a forgotten close blocks only that thread and that grouper`() {
        onFreshThread {
            grouper.groupBy(listOf(e(1, 0)), E::key) // never closed
            assertThrows(IllegalStateException::class.java) { grouper.groupBy(listOf(e(1, 1)), E::key) }
            GroupByUtils.grouper<E>().groupBy(listOf(e(3, 3)), E::key).use { assertEquals(1, it.count) }
        }
        assertEquals(listOf(1L to listOf(1)), listOf(e(1, 1)).snapshot())
    }

    @Test
    fun `the grouping instance is reused per thread and distinct across threads`() {
        val first = grouper.groupBy(listOf(e(1, 0)), E::key).use { it }
        val second = grouper.groupBy(listOf(e(2, 0)), E::key).use { it }
        assertSame(first, second)
        val other = onFreshThread { grouper.groupBy(listOf(e(1, 0)), E::key).use { it } }
        assertNotSame(first, other)
    }

    @Test
    fun `close is idempotent and the next grouping opens normally`() {
        val g = grouper.groupBy(listOf(e(1, 0), e(1, 1)), E::key)
        g.close()
        g.close()
        assertEquals(listOf(4L to listOf(2), 5L to listOf(3)), listOf(e(5, 3), e(4, 2)).snapshot())
    }

    @Test
    fun `a throwing key in the array overload releases the grouping`() {
        val array = arrayOf<Any?>(e(1, 0), e(0, 1))
        repeat(3) {
            assertThrows(ArithmeticException::class.java) { grouper.groupBy(array, 0, 2) { 10 / it.key } }
        }
        grouper.groupBy(array, 0, 2, E::key).use { assertEquals(listOf(0L to listOf(1), 1L to listOf(0)), it.snapshot()) }
    }

    @Test
    fun `concurrent threads on one grouper stay isolated`() {
        val threads = 8
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val start = java.util.concurrent.CountDownLatch(1)
        val workers = (0 until threads).map { t ->
            Thread {
                try {
                    start.await()
                    val random = kotlin.random.Random(t)
                    repeat(2_000) {
                        val n = random.nextInt(0, 200)
                        val spread = if (random.nextBoolean()) 10L else Long.MAX_VALUE
                        val items = (0 until n).map { e(random.nextLong(0, spread), it) }
                        val expected = items.groupBy { it.key }.toSortedMap().map { (k, v) -> k to v.map { it.id } }
                        assertEquals(expected, grouper.groupBy(items, E::key).use { it.snapshot() })
                    }
                } catch (failure: Throwable) {
                    errors.add(failure)
                }
            }.apply { start() }
        }
        start.countDown()
        workers.forEach { it.join() }
        errors.firstOrNull()?.let { throw it }
    }

    @Test
    fun `an oversized grouping is served and its scratch dropped on close`() {
        val small = GroupByUtils.grouper<E>(initialSize = 4)
        val keys = GroupedBy::class.java.getDeclaredField("keys").apply { isAccessible = true }
        val n = 600_000 // above the 4 MB / 8 B cached cap
        val items = (0 until n).map { e(it / 1000L, it) }
        val g = small.groupBy(items, E::key)
        assertEquals(600, g.count)
        assertEquals((599_000 until 600_000).toList(), g.last().map { it.id })
        assertTrue((keys.get(g) as LongArray).size >= n)
        g.close()
        assertEquals(4, (keys.get(g) as LongArray).size)
        assertEquals(listOf(1L to listOf(0)), small.groupBy(listOf(e(1, 0)), E::key).use { it.snapshot() })
    }

    @Test
    fun `groups past the pooled view cap are served one-shot`() {
        val n = 70_000 // above the 65 536 pooled views
        val items = (0 until n).map { e(it.toLong(), it) }
        items.grouped { g ->
            assertEquals(n, g.count)
            assertEquals(listOf(n - 1), g.groupAt(n - 1).map { it.id })
            assertNotSame(g.groupAt(n - 1), g.groupAt(n - 1))
            assertSame(g.groupAt(0), g.groupAt(0))
        }
    }

    @Test
    fun `two groupers never share scratch`() {
        val other = GroupByUtils.grouper<E>()
        grouper.groupBy(listOf(e(1, 0), e(2, 1)), E::key).use { a ->
            other.groupBy(listOf(e(5, 9)), E::key).use { b ->
                assertEquals(listOf(5L to listOf(9)), b.snapshot())
            }
            assertEquals(listOf(1L to listOf(0), 2L to listOf(1)), a.snapshot())
        }
    }

    @Test
    fun `grouping is unusable after close`() {
        val g = grouper.groupBy(listOf(e(1, 0), e(2, 1)), E::key)
        g.close()
        assertThrows(IllegalStateException::class.java) { g.count }
        assertThrows(IllegalStateException::class.java) { g[1L] }
        assertThrows(IllegalStateException::class.java) { g.first() }
        assertThrows(IllegalStateException::class.java) { g.firstKey }
        assertThrows(IllegalStateException::class.java) { g.contains(1L) }
        assertThrows(IllegalStateException::class.java) { g.forEach { _, _ -> } }
        assertDoesNotThrow { g.close() }
    }

    @Test
    fun `a group list kept past close throws instead of reading recycled scratch`() {
        val kept = grouper.groupBy(listOf(e(1, 0), e(1, 1)), E::key).use { it.first() }
        assertThrows(IllegalStateException::class.java) { kept.size }
        assertThrows(IllegalStateException::class.java) { kept[0] }

        grouper.groupBy(listOf(e(5, 5), e(5, 6)), E::key).use { g ->
            assertSame(kept, g.first()) // the view was recycled for the new grouping
            assertEquals(listOf(5, 6), g.first().map { it.id })
        }
    }

    @Test
    fun `close releases the grouped elements`() {
        val g = grouper.groupBy(listOf(e(2, 0), e(0, 1), e(2, 2), e(1, 3)), E::key)
        val values = GroupedBy::class.java.getDeclaredField("values").apply { isAccessible = true }.get(g) as Array<*>
        g.close()
        assertTrue(values.all { it == null })
    }

    @Test
    fun `a throwing key releases the grouping`() {
        repeat(20) {
            assertThrows(ArithmeticException::class.java) {
                grouper.groupBy(listOf(e(1, 0), e(0, 1))) { 10 / it.key }
            }
        }
        assertEquals(listOf(1L to listOf(0)), listOf(e(1, 0)).snapshot())
    }

    @Test
    fun `close from another thread is rejected`() {
        val g = grouper.groupBy(listOf(e(1, 0)), E::key)
        val error = runCatching { onFreshThread { g.close() } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(1, g.count)
        g.close()
    }

    @Test
    fun `group lists are reused across groupings`() {
        val items = listOf(e(2, 0), e(1, 1), e(2, 2))
        val first = grouper.groupBy(items, E::key).use { g -> listOf(g.groupAt(0), g.groupAt(1)) }
        grouper.groupBy(items, E::key).use { g ->
            assertSame(first[0], g.groupAt(0))
            assertSame(first[1], g.groupAt(1))
            assertSame(g.groupAt(0), g[1L])
            assertSame(g.groupAt(1), g.last())
            assertNotSame(g.groupAt(0), g.groupAt(1))
        }
    }

    @Test
    fun `scratch regrows past the initial size`() {
        val small = GroupByUtils.grouper<E>(initialSize = 2)
        for (n in listOf(1, 3, 70, 5, 300)) {
            val items = (0 until n).map { e(((it * 7919) % 13).toLong(), it) }
            val expected = items.groupBy { it.key }.toSortedMap().map { (k, v) -> k to v.map { it.id } }
            assertEquals(expected, small.groupBy(items, E::key).use { it.snapshot() })
        }
    }

    // -- memory -------------------------------------------------------------------------------------

    @Test
    fun `steady state grouping allocates nothing`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        onFreshThread {
            val shapes = listOf(
                (0 until 32).map { e((it / 4).toLong(), it) },             // sorted
                (0 until 32).map { e((it % 8).toLong(), it) },             // dense
                (0 until 32).map { e((it % 8) * 1_000_000L, it) },         // sparse
            )
            val array = shapes[1].toTypedArray<Any?>()
            var sink = 0L
            val op = {
                for (i in 0 until shapes.size) {
                    grouper.groupBy(shapes[i]) { it.key }.use { g ->
                        g.forEach { key, group -> sink += key + group.size + group[0].id }
                    }
                }
                grouper.groupBy(array, 4, 28) { it.key }.use { g -> sink += g[3L].size + g.first().size }
            }
            repeat(100_000) { op() }

            val tid = Thread.currentThread().threadId()
            val iterations = 1_000_000
            val before = bean.getThreadAllocatedBytes(tid)
            repeat(iterations) { op() }
            val perOp = (bean.getThreadAllocatedBytes(tid) - before).toDouble() / iterations

            println("=== GroupByUtils bytes/op (4 groupings) = ${"%.4f".format(perOp)}, sink=$sink ===")
            // The JVM books a few hundred bytes per million calls on its own; anything real is >= 16 B.
            assertTrue(perOp < 0.01, "steady-state grouping should allocate nothing: $perOp bytes/op")
        }
    }
}
