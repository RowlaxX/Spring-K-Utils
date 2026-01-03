package fr.rowlaxx.springkutils.concurrent.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SequentialWorkerPoolTest {
    private val executor = Executors.newFixedThreadPool(4)
    private val pool = SequentialWorkerPool(executor)

    @Test
    fun `should provide same worker for same id`() {
        val worker1 = pool["test"]
        val worker2 = pool["test"]
        assertSame(worker1, worker2)
    }

    @Test
    fun `should provide different workers for different ids`() {
        val worker1 = pool["test1"]
        val worker2 = pool["test2"]
        assertNotSame(worker1, worker2)
    }

    @Test
    fun `workers should execute tasks`() {
        val counter = AtomicInteger(0)
        val future = pool["test"].submitTask {
            counter.incrementAndGet()
        }
        future.get(1, TimeUnit.SECONDS)
        assertEquals(1, counter.get())
    }

    @Test
    fun `flush should remove idle workers`() {
        val worker = pool["test"]
        
        // Wait for task to complete if any (none here)
        pool.flush()
        
        // Should be removed from internal map, so next get should return a different instance
        val worker2 = pool["test"]
        assertNotSame(worker, worker2)
        assertTrue(worker.isRetired)
    }

    @Test
    fun `flush should not remove busy workers`() {
        val worker = pool["test"]
        val latch = java.util.concurrent.CountDownLatch(1)
        val future = worker.submitTask {
            latch.await()
        }
        
        // Give it a moment to start
        Thread.sleep(50)
        
        pool.flush()
        
        val workerSame = pool["test"]
        assertSame(worker, workerSame)
        assertFalse(worker.isRetired)
        
        latch.countDown()
        future.get(1, TimeUnit.SECONDS)
    }
}
