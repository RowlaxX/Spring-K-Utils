package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import fr.rowlaxx.springkutils.array.ArrayUtils.unsafeGroupBy
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * CPU (ns/op) / RAM (B/op) harness for [ArrayUtils.unsafeGroupBy], split by the paths it selects:
 * sorted-dense (time-ordered input), unsorted-dense, sparse, and single-key.
 *
 * Measurement is best-of-[ROUNDS] on one thread; the host is noisy, compare relatively. Not a
 * correctness test.
 *
 *   ./gradlew test --tests '*ArrayUtilsGroupingBench*' -i
 */
class ArrayUtilsGroupingBench {

    private val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean

    @Volatile
    private var sink: Any? = null

    private data class Item(val key: Long, val id: Int)

    /** Timestamps 1000 apart; grouped by `key / 5000`, i.e. ~5 items per bucket, time-ordered. */
    private fun denseSorted(n: Int): List<Item> = (0 until n).map { Item(it.toLong() * 1000L, it) }

    /** Same key distribution as [denseSorted], but shuffled by a fixed stride. */
    private fun denseUnsorted(n: Int): List<Item> {
        val out = ArrayList<Item>(n)
        for (i in 0 until n) {
            val j = (i * 2654435761L) % n
            out.add(Item(j.toLong() * 1000L, i))
        }
        return out
    }

    private fun sparse(n: Int): List<Item> = (0 until n).map { Item(it.toLong() * 1_000_000_000L, it) }

    /** Widely separated keys in shuffled order, so the sparse comparison-sort path is taken. */
    private fun sparseUnsorted(n: Int): List<Item> {
        val out = ArrayList<Item>(n)
        for (i in 0 until n) {
            val j = (i * 2654435761L) % n
            out.add(Item(j.toLong() * 1_000_000_000L, i))
        }
        return out
    }

    private fun singleKey(n: Int): List<Item> = (0 until n).map { Item(7L, it) }

    /** Production-shaped key: a timestamp bucketed by 5000 (a long division per element). */
    private val keyOf: (Item) -> Long = { it.key / 5000L }

    private fun cpu(iterations: Int, rounds: Int, op: () -> Unit): Double {
        repeat(20_000) { op() }
        var best = Double.MAX_VALUE
        repeat(rounds) {
            val t0 = System.nanoTime()
            for (i in 0 until iterations) op()
            best = minOf(best, (System.nanoTime() - t0).toDouble() / iterations)
        }
        return best
    }

    private fun ram(iterations: Int, rounds: Int, op: () -> Unit): Double {
        repeat(5_000) { op() }
        val tid = Thread.currentThread().threadId()
        var best = Double.MAX_VALUE
        repeat(rounds) {
            val before = bean.getThreadAllocatedBytes(tid)
            for (i in 0 until iterations) op()
            best = minOf(best, (bean.getThreadAllocatedBytes(tid) - before).toDouble() / iterations)
        }
        return best
    }

    private fun row(name: String, items: List<Item>) {
        val op = { sink = items.unsafeGroupBy(keyOf).also { it.close() } }
        val ns = cpu(CPU_ITERATIONS, ROUNDS, op)
        val bytes = if (bean.isThreadAllocatedMemorySupported) ram(RAM_ITERATIONS, ROUNDS, op) else -1.0
        println("[GroupBy] %-22s n=%5d ns/op=%9.1f   B/op=%8.1f".format(name, items.size, ns, bytes))
        assertTrue(ns > 0.0)
    }

    @Test
    fun `benchmark unsafeGroupBy paths`() {
        assumeTrue(bean.isThreadAllocatedMemorySupported, "per-thread allocation introspection required")

        println()
        println("=== unsafeGroupBy benchmark (best of $ROUNDS) ===")
        row("dense sorted (4/bucket)", denseSorted(N))
        row("dense unsorted", denseUnsorted(N))
        row("sparse sorted", sparse(N))
        row("sparse unsorted", sparseUnsorted(N))
        row("single key", singleKey(N))
        println()
    }

    private companion object {
        private const val N = 8192
        private const val CPU_ITERATIONS = 5_000
        private const val RAM_ITERATIONS = 2_000
        private const val ROUNDS = 5
    }
}
