package fr.rowlaxx.springkutils.concurrent.core

import com.sun.management.ThreadMXBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Not a correctness test — measures bytes allocated while draining N tasks through the
 * TaskQueue hot path (queue never paused). Run manually:
 *   ./gradlew test --tests "*TaskQueueAllocationBench" -i
 */
class TaskQueueAllocationBench {

    @Test
    fun `measure allocation of draining many tasks`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val n = 200_000

        fun runBatch(): Long {
            val queue = TaskQueue(Dispatchers.Default)
            // Warm + measure on the submitting thread's allocation is not enough (work happens
            // on the loop thread), so measure total allocation across all threads via GC delta.
            val before = totalAllocated(bean)
            val deferreds = (1..n).map { queue.enqueue { it } }
            runBlocking { withTimeout(60_000) { deferreds.forEach { it.await() } } }
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
        // Mirrors the real app hot path: submit { ... } per message, result not awaited.
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val n = 200_000

        fun runBatch(): Long {
            val queue = TaskQueue(Dispatchers.Default)
            val done = AtomicInteger(0)
            val before = totalAllocated(bean)
            repeat(n) { queue.submit { done.incrementAndGet() } }
            // Drain: close then join so we measure only steady-state submit+execute.
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
