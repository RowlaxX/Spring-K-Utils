package fr.rowlaxx.springkutils.concurrent.core

import com.sun.management.ThreadMXBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Not a correctness test — measures bytes allocated while draining N tasks through the
 * TaskQueue hot path (queue never paused). Run manually:
 *   ./gradlew test --tests "*TaskQueueAllocationBench" -i
 *
 * The queue's channel is bounded ([TaskQueue.MAX_PENDING_TASKS]) and rejects once full, so tasks are
 * submitted in chunks that stay comfortably under that cap and each chunk is drained before the next
 * is produced. In-flight never exceeds [CHUNK], so nothing is rejected while still pushing all N tasks
 * through the hot path.
 */
class TaskQueueAllocationBench {

    private companion object {
        /** In-flight tasks per chunk; kept well under the queue's rejection threshold. */
        const val CHUNK = TaskQueue.MAX_PENDING_TASKS / 4
    }

    @Test
    fun `measure allocation of draining many tasks`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val n = 200_000

        fun runBatch(): Long {
            val queue = TaskQueue(Dispatchers.Default)
            // Warm + measure on the submitting thread's allocation is not enough (work happens
            // on the loop thread), so measure total allocation across all threads via GC delta.
            val before = totalAllocated(bean)
            var produced = 0
            while (produced < n) {
                val size = minOf(CHUNK, n - produced)
                val deferreds = (0 until size).map { queue.enqueue { it } }
                runBlocking { withTimeout(60_000) { deferreds.forEach { it.await() } } }
                produced += size
            }
            val after = totalAllocated(bean)
            queue.close()
            return after - before
        }

        // warmup
        runBatch()
        val bytes = runBatch()
        val perTask = bytes.toDouble() / n
        println("=== TaskQueue enqueue+await allocation ===")
        println("tasks            = $n")
        println("total allocated  = ${bytes / 1_000_000.0} MB")
        println("per task         = ${"%.1f".format(perTask)} bytes/task")
    }

    @Test
    fun `measure allocation of submit fire-and-forget path`() {
        // Mirrors the real app hot path: submit { ... } per message, result not awaited per task.
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val n = 200_000

        fun runBatch(): Long {
            val queue = TaskQueue(Dispatchers.Default)
            val done = AtomicInteger(0)
            val before = totalAllocated(bean)
            var produced = 0
            while (produced < n) {
                val size = minOf(CHUNK, n - produced)
                // Fire the chunk without awaiting each task, then pace on the last one: tasks drain in
                // FIFO order on the single loop, so its completion means the whole chunk has drained.
                var last: Job? = null
                repeat(size) { last = queue.submit { done.incrementAndGet() } }
                runBlocking { withTimeout(60_000) { last?.join() } }
                produced += size
            }
            queue.close()
            runBlocking { withTimeout(60_000) { queue.join() } }
            val after = totalAllocated(bean)
            check(done.get() == n) { "expected $n, ran ${done.get()}" }
            return after - before
        }

        runBatch() // warmup
        val bytes = runBatch()
        val perTask = bytes.toDouble() / n
        println("=== TaskQueue submit (fire-and-forget) allocation ===")
        println("tasks            = $n")
        println("total allocated  = ${bytes / 1_000_000.0} MB")
        println("per task         = ${"%.1f".format(perTask)} bytes/task")
    }

    private fun totalAllocated(bean: ThreadMXBean): Long {
        val ids = bean.allThreadIds
        return bean.getThreadAllocatedBytes(ids).sum()
    }
}
