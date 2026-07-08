package fr.rowlaxx.springkutils.concurrent.core

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

/**
 * Pressure / memory test for the bounded [TaskQueue]. Reproduces the original leak shape — a producer
 * firing tasks at a stalled consumer far faster than they drain — and asserts that the buffer cap
 * keeps *retained* memory bounded regardless of how many tasks are submitted.
 *
 * Before the fix the backing channel was `Channel.UNLIMITED`, so every one of these submissions would
 * be held, pinning its captured payload → unbounded heap growth. Now everything past
 * [TaskQueue.MAX_PENDING_TASKS] is rejected and becomes garbage immediately.
 */
class TaskQueueSaturationMemoryTest {

    private val cap = TaskQueue.MAX_PENDING_TASKS

    private fun usedHeap(): Long {
        System.gc(); Thread.sleep(100)
        System.gc(); Thread.sleep(50)
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    /** Blocks the single consumer until the returned latch is released (see the saturation test). */
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

    @Test
    fun `flooding a stalled queue keeps retained memory bounded`() {
        val queue = TaskQueue(Dispatchers.Default)
        val release = queue.blockConsumer()

        val payloadBytes = 1024
        val submitted = 300_000              // ~300 MB if every task were retained
        val unboundedRetention = submitted.toLong() * payloadBytes

        val accepted = ArrayList<Deferred<Int>>(cap + 16)
        var rejected = 0

        val baseline = usedHeap()

        repeat(submitted) { i ->
            val payload = ByteArray(payloadBytes) // captured by the task lambda
            val d = queue.enqueue { payload.size + i }
            // Rejected tasks (and their captured payloads) are dropped here → eligible for GC.
            if (d.isCompleted) rejected++ else accepted.add(d)
        }

        // 1) The mechanism that bounds memory: acceptance is capped no matter the submission volume.
        assertEquals(cap, accepted.size, "no more than the cap may be buffered")
        assertEquals(submitted - cap, rejected, "the overwhelming majority must be rejected")

        // 2) The consequence: live heap holds ~cap payloads, nowhere near the unbounded figure.
        val retained = usedHeap() - baseline
        val ceiling = 64L * 1024 * 1024
        println("=== TaskQueue saturation memory ===")
        println("submitted        = $submitted")
        println("accepted (held)  = ${accepted.size}")
        println("rejected         = $rejected")
        println("retained heap    = ${retained / 1_000_000.0} MB")
        println("unbounded ≈      = ${unboundedRetention / 1_000_000} MB")
        assertTrue(
            retained < ceiling,
            "retained heap ${retained / 1_000_000.0} MB should stay well under ${ceiling / 1_000_000} MB " +
                "(unbounded would be ~${unboundedRetention / 1_000_000} MB)",
        )

        // Drain and clean up.
        release.countDown()
        runBlocking { withTimeout(30_000) { accepted.forEach { it.await() } } }
        queue.close()
    }

    @Test
    fun `a paused queue rejects the overflow instead of buffering it forever`() {
        // A paused queue pulls at most one task into the loop, then parks — the classic
        // "never drains" case that previously grew without bound.
        val queue = TaskQueue(Dispatchers.Default, paused = true)

        val submitted = 100_000
        var accepted = 0
        var rejected = 0
        repeat(submitted) { i ->
            val d = queue.enqueue { i }
            // A paused/blocked queue never completes a buffered task, so "completed immediately"
            // means rejected. (isCancelled is true for any exceptional completion, so it can't be
            // used to tell a rejection apart here.)
            if (d.isCompleted) rejected++ else accepted++
        }

        // One task may have been pulled into the parked loop, so allow cap (+1) accepted.
        assertTrue(accepted <= cap + 1, "paused queue accepted $accepted, exceeds the cap")
        assertEquals(submitted - accepted, rejected)
        assertTrue(rejected > 0, "flooding a paused queue must trigger rejections")

        queue.close()
    }
}
