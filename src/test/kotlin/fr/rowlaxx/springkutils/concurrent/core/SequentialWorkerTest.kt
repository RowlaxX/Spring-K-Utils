package fr.rowlaxx.springkutils.concurrent.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class SequentialWorkerTest {
    private val executor = Executors.newFixedThreadPool(4)
    private val worker = SequentialWorker(executor)

    @Test
    fun `tasks should execute sequentially`() {
        val counter = AtomicInteger(0)
        val results = mutableListOf<Int>()
        val futures = mutableListOf<CompletableFuture<*>>()

        for (i in 1..10) {
            futures.add(worker.submitTask {
                Thread.sleep(10)
                results.add(i)
                counter.incrementAndGet()
            })
        }

        CompletableFuture.allOf(*futures.toTypedArray()).get(5, TimeUnit.SECONDS)

        assertEquals(10, counter.get())
        assertEquals((1..10).toList(), results)
    }

    @Test
    fun `async tasks should execute sequentially`() {
        val counter = AtomicInteger(0)
        val results = mutableListOf<Int>()
        val futures = mutableListOf<CompletableFuture<*>>()

        for (i in 1..10) {
            futures.add(worker.submitAsyncTask {
                CompletableFuture.supplyAsync({
                    Thread.sleep(10)
                    results.add(i)
                    counter.incrementAndGet()
                    i
                }, executor)
            })
        }

        CompletableFuture.allOf(*futures.toTypedArray()).get(5, TimeUnit.SECONDS)

        assertEquals(10, counter.get())
        assertEquals((1..10).toList(), results)
    }
    
    @Test
    fun `runTaskIfIdle should fail if busy`() {
        val latch = CountDownLatch(1)
        val firstTask = worker.submitTask {
            latch.countDown()
            Thread.sleep(100)
        }
        
        latch.await()
        val secondTask = worker.runTaskIfIdle {
            "should fail"
        }
        
        assertTrue(secondTask.isCompletedExceptionally)
        assertThrows<ExecutionException> { secondTask.get() }
        firstTask.get()
    }

    @Test
    fun `runAsyncTaskIfIdle should fail if busy`() {
        val latch = CountDownLatch(1)
        val firstTask = worker.submitTask {
            latch.countDown()
            Thread.sleep(100)
        }

        latch.await()
        val secondTask = worker.runAsyncTaskIfIdle {
            CompletableFuture.completedFuture("should fail")
        }

        assertTrue(secondTask.isCompletedExceptionally)
        assertThrows<ExecutionException> { secondTask.get() }
        firstTask.get()
    }

    @Test
    fun `retire should cancel pending tasks and prevent new ones`() {
        val latch = CountDownLatch(1)
        val firstTaskStarted = CountDownLatch(1)
        
        val firstTask = worker.submitTask {
            firstTaskStarted.countDown()
            latch.await()
        }

        val secondTask = worker.submitTask { "second" }
        val thirdTask = worker.submitAsyncTask { CompletableFuture.completedFuture("third") }

        firstTaskStarted.await()
        assertEquals(2, worker.pendingTasksCount)

        worker.retire()
        assertTrue(worker.isRetired)
        assertEquals(0, worker.pendingTasksCount)

        assertTrue(secondTask.isCompletedExceptionally)
        assertTrue(thirdTask.isCompletedExceptionally)

        val fourthTask = worker.submitTask { "fourth" }
        assertTrue(fourthTask.isCompletedExceptionally)

        latch.countDown()
        assertEquals("first", "first") // just to wait for firstTask
        firstTask.get()
        assertFalse(firstTask.isCompletedExceptionally)
    }

    @Test
    fun `exception in sync task should not stop the worker`() {
        val firstTask = worker.submitTask {
            throw RuntimeException("fail")
        }

        val secondTask = worker.submitTask {
            "success"
        }

        assertThrows<ExecutionException> { firstTask.get() }
        assertEquals("success", secondTask.get())
    }

    @Test
    fun `exception in async task should not stop the worker`() {
        val firstTask = worker.submitAsyncTask {
            CompletableFuture.failedFuture<String>(RuntimeException("fail"))
        }

        val secondTask = worker.submitTask {
            "success"
        }

        assertThrows<ExecutionException> { firstTask.get() }
        assertEquals("success", secondTask.get())
    }

    @Test
    fun `async task that throws immediately should be handled`() {
        val firstTask = worker.submitAsyncTask<String> {
            throw RuntimeException("immediate fail")
        }

        val secondTask = worker.submitTask {
            "success"
        }

        assertThrows<ExecutionException> { firstTask.get() }
        assertEquals("success", secondTask.get())
    }
    
    @Test
    fun `worker should report correct state`() {
        assertFalse(worker.isRunning)
        assertEquals(0, worker.pendingTasksCount)
        
        val latch = CountDownLatch(1)
        val task = worker.submitTask {
            latch.await()
        }
        
        // Give it a moment to start
        Thread.sleep(50)
        assertTrue(worker.isRunning)
        
        val secondTask = worker.submitTask { }
        assertEquals(1, worker.pendingTasksCount)
        
        latch.countDown()
        task.get()
        secondTask.get()
        
        // Give it a moment to update state
        Thread.sleep(50)
        assertFalse(worker.isRunning)
        assertEquals(0, worker.pendingTasksCount)
    }

    @Test
    fun `stress test concurrent submissions`() {
        val submissionThreads = 10
        val tasksPerThread = 100
        val counter = AtomicInteger(0)
        val submissionExecutor = Executors.newFixedThreadPool(submissionThreads)
        val futures = mutableListOf<CompletableFuture<*>>()

        val startLatch = CountDownLatch(1)

        repeat (submissionThreads) {
            submissionExecutor.submit {
                startLatch.await()
                repeat(tasksPerThread) {
                    val f = worker.submitTask {
                        counter.incrementAndGet()
                    }
                    synchronized(futures) {
                        futures.add(f)
                    }
                }
            }
        }

        startLatch.countDown()
        submissionExecutor.shutdown()
        submissionExecutor.awaitTermination(5, TimeUnit.SECONDS)

        CompletableFuture.allOf(*futures.toTypedArray()).get(10, TimeUnit.SECONDS)
        assertEquals(submissionThreads * tasksPerThread, counter.get())
        assertFalse(worker.isRunning)
        assertEquals(0, worker.pendingTasksCount)
    }

    @Test
    fun `cancelled task should not execute`() {
        val taskExecuted = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        // Block the worker with a first task
        worker.submitTask {
            latch.await()
        }

        // Submit a second task and cancel it immediately
        val future = worker.submitTask {
            taskExecuted.set(true)
        }
        future.cancel(false)

        // Unblock the worker
        latch.countDown()

        // Give it some time to process
        Thread.sleep(100)

        assertFalse(taskExecuted.get(), "Task should not have executed because it was cancelled")
    }

    @Test
    fun `cancelled async task should not execute`() {
        val taskExecuted = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        // Block the worker with a first task
        worker.submitTask {
            latch.await()
        }

        // Submit a second task and cancel it immediately
        val future = worker.submitAsyncTask {
            taskExecuted.set(true)
            CompletableFuture.completedFuture(Unit)
        }
        future.cancel(false)

        // Unblock the worker
        latch.countDown()

        // Give it some time to process
        Thread.sleep(100)

        assertFalse(taskExecuted.get(), "Async task should not have executed because it was cancelled")
    }

    @Test
    fun `cancelling running async task should move to next task`() {
        val nextTaskExecuted = AtomicBoolean(false)
        val innerFuture = CompletableFuture<Unit>()

        val future = worker.submitAsyncTask {
            innerFuture
        }

        val nextFuture = worker.submitTask {
            nextTaskExecuted.set(true)
        }

        // Give it some time to start the first task
        Thread.sleep(50)

        // Cancel the first future
        future.cancel(false)

        // Wait for next task
        nextFuture.get(1, TimeUnit.SECONDS)

        assertTrue(nextTaskExecuted.get(), "Next task should execute after the previous one was cancelled")
    }

    @Test
    fun `worker should respect isEnabled state`() {
        val disabledWorker = SequentialWorker(executor, enabled = false)
        assertFalse(disabledWorker.isEnabled)

        val taskExecuted = AtomicBoolean(false)
        val future = disabledWorker.submitTask {
            taskExecuted.set(true)
            "done"
        }

        // Give it some time to potentially execute
        Thread.sleep(100)
        assertFalse(taskExecuted.get(), "Task should not execute when worker is disabled")
        assertFalse(future.isDone)

        disabledWorker.enabled(true)
        assertTrue(disabledWorker.isEnabled)

        assertEquals("done", future.get(1, TimeUnit.SECONDS))
        assertTrue(taskExecuted.get(), "Task should execute after worker is enabled")
    }

    @Test
    fun `disabling worker should stop picking up new tasks`() {
        val counter = AtomicInteger(0)
        val latch = CountDownLatch(1)

        val firstTask = worker.submitTask {
            latch.await()
            counter.incrementAndGet()
        }

        val secondTask = worker.submitTask {
            counter.incrementAndGet()
        }

        // Give it time to start first task
        Thread.sleep(50)

        worker.enabled(false)
        assertFalse(worker.isEnabled)

        latch.countDown()
        firstTask.get(1, TimeUnit.SECONDS)
        assertEquals(1, counter.get())

        // Give it time to potentially pick up second task
        Thread.sleep(100)
        assertEquals(1, counter.get(), "Second task should not have started because worker is disabled")
        assertFalse(secondTask.isDone)

        worker.enabled(true)
        secondTask.get(1, TimeUnit.SECONDS)
        assertEquals(2, counter.get())
    }
}
