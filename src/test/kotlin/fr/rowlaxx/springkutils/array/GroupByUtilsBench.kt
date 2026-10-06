package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * CPU (ns/op) / RAM (B/op) harness for [GroupByUtils.Grouper.groupBy], split by the paths it selects
 * (sorted, dense, sparse, single key) plus the small batches production actually sees. Every op also
 * walks the groups, since that is where the view generation checks run.
 *
 * Best-of-[ROUNDS] on one thread; the host is noisy (±15%), compare relatively.
 *
 *   ./gradlew test --tests '*GroupByUtilsBench*' -i
 */
class GroupByUtilsBench {

    private val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean

    @Volatile
    private var sink = 0L

    private data class Item(val key: Long, val id: Int)

    /** Timestamps 1000 apart; grouped by `key / 5000`, i.e. ~5 items per bucket, time-ordered. */
    private fun denseSorted(n: Int): List<Item> = (0 until n).map { Item(it.toLong() * 1000L, it) }

    /** Same keys as [denseSorted], shuffled by a fixed stride. */
    private fun denseUnsorted(n: Int): List<Item> = (0 until n).map { Item(((it * 2654435761L) % n) * 1000L, it) }

    private fun sparse(n: Int): List<Item> = (0 until n).map { Item(it.toLong() * 1_000_000_000L, it) }

    /** Widely separated keys in shuffled order, so the merge-sort path is taken. */
    private fun sparseUnsorted(n: Int): List<Item> =
        (0 until n).map { Item(((it * 2654435761L) % n) * 1_000_000_000L, it) }

    private fun singleKey(n: Int): List<Item> = (0 until n).map { Item(7L, it) }

    /** TradeAccumulator-shaped: 50 markets keyed by a 64-bit hash, interleaved. */
    private fun markets(n: Int): List<Item> = (0 until n).map { Item((it % 50) * -7046029254386353131L, it) }

    private val grouper = GroupByUtils.grouper<Item>()

    private fun cpu(iterations: Int, op: () -> Unit): Double {
        repeat(iterations * 4) { op() }
        var best = Double.MAX_VALUE
        repeat(ROUNDS) {
            val t0 = System.nanoTime()
            for (i in 0 until iterations) op()
            best = minOf(best, (System.nanoTime() - t0).toDouble() / iterations)
        }
        return best
    }

    private fun ram(iterations: Int, op: () -> Unit): Double {
        repeat(iterations) { op() }
        val tid = Thread.currentThread().threadId()
        var best = Double.MAX_VALUE
        repeat(ROUNDS) {
            val before = bean.getThreadAllocatedBytes(tid)
            for (i in 0 until iterations) op()
            best = minOf(best, (bean.getThreadAllocatedBytes(tid) - before).toDouble() / iterations)
        }
        return best
    }

    private fun row(name: String, n: Int, op: () -> Unit): Double {
        val iterations = maxOf(200, ELEMENTS_PER_ROUND / maxOf(n, 1))
        val ns = cpu(iterations, op)
        val bytes = ram(iterations, op)
        println("[GroupBy] %-28s n=%5d ns/op=%10.1f ns/elem=%6.2f B/op=%9.2f".format(name, n, ns, ns / n, bytes))
        return bytes
    }

    private fun consume(items: List<Item>, divisor: Long) {
        var s = 0L
        grouper.groupBy(items) { it.key / divisor }.use { g -> g.forEach { k, l -> s += k + l.size + l[0].id } }
        sink = s
    }

    @Test
    fun `benchmark groupBy paths`() {
        assumeTrue(bean.isThreadAllocatedMemorySupported, "per-thread allocation introspection required")

        val shapes = listOf(
            "dense sorted" to denseSorted(N),
            "dense unsorted" to denseUnsorted(N),
            "sparse sorted" to sparse(N),
            "sparse unsorted" to sparseUnsorted(N),
            "single key" to singleKey(N),
            "dense sorted" to denseSorted(32),
            "dense unsorted" to denseUnsorted(32),
            "markets(50)" to markets(1000),
            "markets(50)" to markets(100),
        )

        println()
        println("=== groupBy benchmark (best of $ROUNDS) ===")
        for ((name, items) in shapes) {
            // Non-capturing key: one lambda instance for the whole run, so the grouping alone is measured.
            val bytes = row(name, items.size) {
                var s = 0L
                grouper.groupBy(items) { it.key / 5000L }.use { g -> g.forEach { k, l -> s += k + l.size + l[0].id } }
                sink = s
            }
            assertEquals(0.0, bytes, 0.01, "$name: steady-state grouping should allocate nothing")
        }

        val array = markets(1000).toTypedArray<Any?>()
        row("markets(50) array 100..900", 800) {
            var s = 0L
            grouper.groupBy(array, 100, 900) { it.key }.use { g -> g.forEach { k, l -> s += k + l.size } }
            sink = s
        }

        // groupBy is not inline: a key capturing a local (`it.timeId / ratio`) is a fresh lambda per call,
        // unless escape analysis removes it. Printed, not gated.
        row("markets(50) capturing key", 100) { consume(markets100, 1L) }

        // Reference only: what the stdlib pays for the same work.
        row("stdlib groupBy markets(50)", 100) {
            var s = 0L
            markets100.groupBy { it.key }.forEach { (k, l) -> s += k + l.size }
            sink = s
        }
        println()
    }

    private val markets100 = markets(100)

    private companion object {
        private const val N = 8192
        private const val ELEMENTS_PER_ROUND = 4_000_000
        private const val ROUNDS = 5
    }
}
