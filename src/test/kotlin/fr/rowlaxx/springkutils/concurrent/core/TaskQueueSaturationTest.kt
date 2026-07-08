package fr.rowlaxx.springkutils.concurrent.core

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Correctness tests for the bounded-queue / saturation behaviour introduced with
 * [TaskQueue.MAX_PENDING_TASKS]: once the buffer is full, further tasks are rejected (their
 * [Deferred] completes with [RejectedExecutionException]) instead of accumulating without bound.
 */
class TaskQueueSaturationTest {

    private val cap = TaskQueue.MAX_PENDING_TASKS

    private fun newQueue() = TaskQueue(Dispatchers.Default)

    /**
     * Enqueues a task that pins the single consumer until the returned latch is released. Once this
     * returns, the loop has pulled that task out of the buffer and is blocked running it — so the
     * channel buffer is empty and every subsequent [TaskQueue.enqueue] either buffers (accepted) or is
     * rejected, deterministically, with nothing draining underneath.
     */
    private fun TaskQueue.blockConsumer(): CountDownLatch {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        enqueue {
            started.countDown()
            release.await()
        }
        started.await()
        return release
    }

    // Note: a Deferred completed exceptionally with ANY throwable reports isCancelled == true in
    // kotlinx — so we distinguish a saturation rejection from anything else by the exception type,
    // never by isCancelled.
    private fun isRejected(deferred: Deferred<*>): Boolean {
        if (!deferred.isCompleted) return false
        // Already completed, so await() returns/throws immediately.
        return try {
            runBlocking { deferred.await() }
            false
        } catch (e: RejectedExecutionException) {
            true
        } catch (e: Throwable) {
            false
        }
    }

    @Test
    fun `accepts exactly MAX_PENDING_TASKS then rejects the overflow`() {
        val queue = newQueue()
        val release = queue.blockConsumer()

        val extra = 100
        val accepted = mutableListOf<Deferred<Int>>()
        var rejected = 0

        repeat(cap + extra) { i ->
            val d = queue.enqueue { i }
            if (d.isCompleted) rejected++ else accepted.add(d)
        }

        assertEquals(cap, accepted.size, "buffer must accept exactly the cap")
        assertEquals(extra, rejected, "everything past the cap must be rejected")

        release.countDown()
        runBlocking { withTimeout(10_000) { accepted.forEach { it.await() } } }
        queue.close()
    }

    @Test
    fun `a rejected task completes with RejectedExecutionException`() {
        val queue = newQueue()
        val release = queue.blockConsumer()

        // Fill the buffer to the cap.
        val accepted = (0 until cap).map { queue.enqueue { it } }

        // The next one overflows and must be rejected.
        val overflow = queue.enqueue { 1 }
        assertTrue(overflow.isCompleted, "a saturated enqueue is completed immediately")
        assertThrows<RejectedExecutionException> {
            runBlocking { withTimeout(1_000) { overflow.await() } }
        }

        release.countDown()
        runBlocking { withTimeout(10_000) { accepted.forEach { it.await() } } }
        queue.close()
    }

    @Test
    fun `rejected tasks are never executed`() {
        val queue = newQueue()
        val release = queue.blockConsumer()
        val executed = AtomicInteger(0)

        val extra = 500
        val accepted = mutableListOf<Deferred<Int>>()
        repeat(cap + extra) {
            val d = queue.enqueue { executed.incrementAndGet() }
            if (!d.isCompleted) accepted.add(d)
        }
        assertEquals(cap, accepted.size)

        release.countDown()
        runBlocking { withTimeout(10_000) { accepted.forEach { it.await() } } }

        // Only the accepted tasks ever ran; the rejected overflow bodies were never invoked.
        assertEquals(cap, executed.get())
        queue.close()
    }

    @Test
    fun `submit past the cap returns a failed job`() {
        val queue = newQueue()
        val release = queue.blockConsumer()

        repeat(cap) { queue.enqueue { it } } // fill
        val job = queue.submit { /* never runs */ }

        assertTrue(job.isCompleted)
        assertThrows<RejectedExecutionException> {
            runBlocking { withTimeout(1_000) { (job as Deferred<*>).await() } }
        }

        release.countDown()
        queue.close()
    }

    @Test
    fun `queue recovers and accepts new work after it drains below the cap`() {
        val queue = newQueue()
        val release = queue.blockConsumer()

        // Saturate: fill the buffer and force at least one rejection.
        val accepted = (0 until cap).map { queue.enqueue { it } }
        assertTrue(isRejected(queue.enqueue { -1 }), "queue should be saturated here")

        // Let it drain completely.
        release.countDown()
        runBlocking { withTimeout(15_000) { accepted.forEach { it.await() } } }

        // Now that it has drained below the low-water mark, new work is accepted again.
        val afterRecovery = queue.enqueue { 123 }
        assertEquals(123, runBlocking { withTimeout(5_000) { afterRecovery.await() } })
        queue.close()
    }

    @Test
    fun `concurrent submissions past the cap stay bounded and are fully accounted for`() {
        val queue = newQueue()
        val release = queue.blockConsumer()

        val submitters = 8
        val perThread = cap // together 8x the cap
        val start = CountDownLatch(1)
        val accepted = Collections.synchronizedList(mutableListOf<Deferred<Int>>())
        val rejected = AtomicInteger(0)

        val threads = (1..submitters).map {
            Thread {
                start.await()
                repeat(perThread) { i ->
                    val d = queue.enqueue { i }
                    if (d.isCompleted) rejected.incrementAndGet() else accepted.add(d)
                }
            }.apply { start() }
        }
        start.countDown()
        threads.forEach { it.join() }

        val total = submitters * perThread
        assertEquals(cap, accepted.size, "no more than the cap may be buffered, even under contention")
        assertEquals(total - cap, rejected.get(), "every submission is either accepted or rejected, none lost")

        release.countDown()
        runBlocking { withTimeout(20_000) { accepted.forEach { it.await() } } }
        queue.close()
    }
}
