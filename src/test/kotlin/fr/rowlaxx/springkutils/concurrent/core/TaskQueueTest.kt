package fr.rowlaxx.springkutils.concurrent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TaskQueueTest {

    private fun newQueue(paused: Boolean = false) =
        TaskQueue(Dispatchers.Default, paused = paused)

    private fun <T> await(deferred: kotlinx.coroutines.Deferred<T>, timeoutMs: Long = 5000): T =
        runBlocking { withTimeout(timeoutMs) { deferred.await() } }

    @Test
    fun `enqueue should return the task result`() {
        val queue = newQueue()
        val result = await(queue.enqueue { 42 })
        assertEquals(42, result)
        queue.close()
    }

    @Test
    fun `enqueue should support suspending tasks`() {
        val queue = newQueue()
        val result = await(queue.enqueue {
            delay(20)
            "done"
        })
        assertEquals("done", result)
        queue.close()
    }

    @Test
    fun `tasks should execute sequentially in FIFO order`() {
        val queue = newQueue()
        val results = Collections.synchronizedList(mutableListOf<Int>())

        val deferreds = (1..50).map { i ->
            queue.enqueue {
                delay(1)
                results.add(i)
                i
            }
        }

        runBlocking { withTimeout(5000) { deferreds.forEach { it.await() } } }

        assertEquals((1..50).toList(), results)
        queue.close()
    }

    @Test
    fun `submit should return a job that completes`() {
        val queue = newQueue()
        val executed = AtomicBoolean(false)
        val job = queue.submit { executed.set(true) }

        runBlocking { withTimeout(5000) { job.join() } }

        assertTrue(job.isCompleted)
        assertTrue(executed.get())
        queue.close()
    }

    @Test
    fun `exception in a task should fail only that task and not stop the queue`() {
        val queue = newQueue()

        val failing = queue.enqueue<Unit> { throw RuntimeException("boom") }
        val succeeding = queue.enqueue { "ok" }

        assertThrows<RuntimeException> { await(failing) }
        assertEquals("ok", await(succeeding))
        queue.close()
    }

    @Test
    fun `paused queue should not execute tasks until resumed`() {
        val queue = newQueue(paused = true)
        val executed = AtomicBoolean(false)

        val deferred = queue.enqueue { executed.set(true); "value" }

        // Give the loop a chance to (not) run the task.
        Thread.sleep(150)
        assertFalse(executed.get(), "Task must not run while paused")
        assertFalse(deferred.isCompleted)

        queue.resume()

        assertEquals("value", await(deferred))
        assertTrue(executed.get())
        queue.close()
    }

    @Test
    fun `tasks enqueued while paused run in FIFO order after resume`() {
        val queue = newQueue(paused = true)
        val results = Collections.synchronizedList(mutableListOf<Int>())

        val deferreds = (1..20).map { i ->
            queue.enqueue { results.add(i); i }
        }

        Thread.sleep(100)
        assertTrue(results.isEmpty(), "Nothing should run while paused")

        queue.resume()
        runBlocking { withTimeout(5000) { deferreds.forEach { it.await() } } }

        assertEquals((1..20).toList(), results)
        queue.close()
    }

    @Test
    fun `pause then resume should be observable through running behaviour`() {
        val queue = newQueue()
        val firstStarted = CountDownLatch(1)
        val release = CountDownLatch(1)

        // Occupy the loop so we can pause deterministically afterwards.
        val first = queue.enqueue {
            firstStarted.countDown()
            release.await()
        }
        firstStarted.await()

        queue.pause()
        val counter = AtomicInteger(0)
        val second = queue.enqueue { counter.incrementAndGet() }

        release.countDown()
        await(first)

        Thread.sleep(150)
        assertEquals(0, counter.get(), "Second task must wait while paused")

        queue.resume()
        await(second)
        assertEquals(1, counter.get())
        queue.close()
    }

    @Test
    fun `enqueue after close should complete exceptionally`() {
        val queue = newQueue()
        queue.close()

        val deferred = queue.enqueue { 1 }
        assertTrue(deferred.isCancelled)
        assertThrows<CancellationException> { await(deferred) }
    }

    @Test
    fun `close while running should drain and run buffered tasks`() {
        val queue = newQueue()
        val results = Collections.synchronizedList(mutableListOf<Int>())

        val deferreds = (1..10).map { i ->
            queue.enqueue {
                delay(5)
                results.add(i)
                i
            }
        }
        queue.close()

        runBlocking { withTimeout(5000) { deferreds.forEach { it.await() } } }
        assertEquals((1..10).toList(), results)

        runBlocking { withTimeout(5000) { queue.join() } }
    }

    @Test
    fun `close while paused should abort buffered tasks`() {
        val queue = newQueue(paused = true)
        val executed = AtomicBoolean(false)

        val deferred = queue.enqueue { executed.set(true) }
        Thread.sleep(100)

        queue.close()

        assertThrows<CancellationException> { await(deferred) }
        assertTrue(deferred.isCancelled)
        Thread.sleep(100)
        assertFalse(executed.get(), "Task must not run when closed while paused")

        runBlocking { withTimeout(5000) { queue.join() } }
    }

    @Test
    fun `cancelled deferred should be skipped`() {
        val queue = newQueue(paused = true)
        val executed = AtomicBoolean(false)

        val deferred = queue.enqueue { executed.set(true) }
        // Let the loop reach the pause-gate holding this task.
        Thread.sleep(100)
        deferred.cancel()

        queue.resume()
        Thread.sleep(150)

        assertFalse(executed.get(), "Cancelled task must not execute")
        assertTrue(deferred.isCancelled)
        queue.close()
    }

    @Test
    fun `join should return after close drains the queue`() {
        val queue = newQueue()
        val counter = AtomicInteger(0)

        repeat(100) { queue.enqueue { counter.incrementAndGet() } }
        queue.close()

        runBlocking { withTimeout(5000) { queue.join() } }
        assertEquals(100, counter.get())
    }

    @Test
    fun `stress test concurrent submissions preserve count`() {
        val queue = newQueue()
        val counter = AtomicInteger(0)
        val submitters = 8
        val perThread = 200
        val latch = CountDownLatch(1)
        val deferreds = Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Deferred<Int>>())

        val threads = (1..submitters).map {
            Thread {
                latch.await()
                repeat(perThread) {
                    deferreds.add(queue.enqueue { counter.incrementAndGet() })
                }
            }.apply { start() }
        }

        latch.countDown()
        threads.forEach { it.join() }

        runBlocking { withTimeout(10000) { deferreds.forEach { it.await() } } }
        assertEquals(submitters * perThread, counter.get())
        queue.close()
    }
}
