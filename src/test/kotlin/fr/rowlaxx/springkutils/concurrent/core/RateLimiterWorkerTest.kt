package fr.rowlaxx.springkutils.concurrent.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import org.springframework.scheduling.Trigger
import org.springframework.scheduling.TriggerContext
import org.springframework.scheduling.support.PeriodicTrigger
import org.springframework.scheduling.support.CronTrigger
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class RateLimiterWorkerTest {
    private val executor = Executors.newSingleThreadScheduledExecutor()

    @Test
    fun `tasks should execute immediately if under limit`() {
        val limiter = RateLimiterWorker(executor, 10, FixedTrigger(1000))
        val result = limiter.submitTask(5) { "success" }.get(1, TimeUnit.SECONDS)
        assertEquals("success", result)
    }

    @Test
    fun `tasks should be delayed if exceeding limit`() {
        val limiter = RateLimiterWorker(executor, 10, FixedTrigger(100))
        val counter = AtomicInteger(0)

        // Use up the limit
        limiter.submitTask(10) { counter.incrementAndGet() }
        
        // This one should be delayed
        val delayedTask = limiter.submitTask(1) { counter.incrementAndGet() }
        
        // Should not be done yet
        assertFalse(delayedTask.isDone)
        assertEquals(1, counter.get())

        // Wait for reset (FixedTrigger is 100ms)
        val result = delayedTask.get(500, TimeUnit.MILLISECONDS)
        assertEquals(2, result)
        assertEquals(2, counter.get())
    }

    @Test
    fun `async tasks should also respect limit`() {
        val limiter = RateLimiterWorker(executor, 10, FixedTrigger(100))
        val counter = AtomicInteger(0)

        limiter.submitAsyncTask(10) { 
            CompletableFuture.supplyAsync { 
                counter.incrementAndGet()
            }
        }
        
        val delayedTask = limiter.submitAsyncTask(1) {
            CompletableFuture.supplyAsync {
                counter.incrementAndGet()
            }
        }
        
        assertFalse(delayedTask.isDone)
        
        val result = delayedTask.get(500, TimeUnit.MILLISECONDS)
        assertEquals(2, result)
    }

    @Test
    fun `multiple tasks should be queued and executed in order after reset`() {
        val limiter = RateLimiterWorker(executor, 10, FixedTrigger(100))
        val results = mutableListOf<Int>()
        val futures = mutableListOf<CompletableFuture<Int>>()

        for (i in 1..25) { // 25 tasks of weight 1, limit 10. Should take 3 periods.
            futures.add(limiter.submitTask(1) {
                synchronized(results) { results.add(i) }
                i
            })
        }

        CompletableFuture.allOf(*futures.toTypedArray()).get(2, TimeUnit.SECONDS)
        
        assertEquals(25, results.size)
        assertEquals((1..25).toList(), results)
    }

    @Test
    fun `tasks with weight larger than limit should wait indefinitely but not block other smaller tasks if they were first? No, it blocks because it's a queue`() {
        // Actually, if a task is larger than the limit, it will never execute with current implementation
        // and it will block the whole queue because it's at the head.
        // Let's verify this behavior.
        val limiter = RateLimiterWorker(executor, 5, FixedTrigger(100))
        val task = limiter.submitTask(10) { "wont happen" }
        val smallerTask = limiter.submitTask(1) { "blocked" }

        Thread.sleep(200)
        assertFalse(task.isDone)
        assertFalse(smallerTask.isDone)
    }

    @Test
    fun `should work with PeriodicTrigger fixed rate`() {
        val trigger = PeriodicTrigger(Duration.ofMillis(500))
        trigger.setFixedRate(true)
        val limiter = RateLimiterWorker(executor, 10, trigger)
        val counter = AtomicInteger(0)

        limiter.submitTask(10) { counter.incrementAndGet() }
        val delayedTask = limiter.submitTask(1) { counter.incrementAndGet() }

        val result = delayedTask.get(2, TimeUnit.SECONDS)
        assertEquals(2, result)
        assertEquals(2, counter.get())
    }

    @Test
    fun `should work with CronTrigger`() {
        // Cron trigger every second
        val limiter = RateLimiterWorker(executor, 10, CronTrigger("* * * * * *"))
        val counter = AtomicInteger(0)

        limiter.submitTask(10) { counter.incrementAndGet() }
        val delayedTask = limiter.submitTask(1) { counter.incrementAndGet() }

        // We don't assert isDone here because depending on when we are in the second, 
        // it might reset very quickly.
        
        // Wait for next second. 2 seconds should be enough.
        val result = delayedTask.get(2, TimeUnit.SECONDS)
        assertEquals(2, result)
        assertEquals(2, counter.get())
    }

    private class FixedTrigger(val periodMs: Long) : Trigger {
        override fun nextExecution(triggerContext: TriggerContext): Instant? {
            val last = triggerContext.lastActualExecution() ?: Instant.now()
            return last.plusMillis(periodMs)
        }
    }
}
