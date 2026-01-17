package fr.rowlaxx.springkutils.concurrent.core

import org.springframework.scheduling.Trigger
import org.springframework.scheduling.support.SimpleTriggerContext
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A worker that limits the rate of task execution based on a weight limit and a reset trigger.
 *
 * Each task has an associated weight. The worker ensures that the sum of weights of tasks
 * executed within a period (defined by the [reset] trigger) does not exceed the [limit].
 *
 * Tasks that would exceed the limit are queued and executed once the limit is reset.
 *
 * Note: If a task has a weight greater than the [limit], it will never be executed and will block the queue.
 *
 * This class is thread-safe.
 *
 * @property executor The [ScheduledExecutorService] used to execute tasks and schedule resets.
 * @property limit The maximum total weight allowed per period.
 * @property reset The [Trigger] that defines when the weight counter should be reset.
 */
class RateLimiterWorker(
    private val executor: ScheduledExecutorService,
    val limit: Int,
    val reset: Trigger,
) {
    private val queue: Deque<InternalTask<*>> = ArrayDeque()
    private val currentWeight = AtomicInteger(0)
    private val isProcessing = AtomicBoolean(false)
    private val triggerContext = SimpleTriggerContext()

    init {
        scheduleNextReset()
    }

    /**
     * Submits a synchronous task with a given weight.
     *
     * @param weight The weight of the task.
     * @param task The task to execute.
     * @return A [CompletableFuture] that completes with the task result.
     */
    fun <T> submitTask(weight: Int, task: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val internalTask = SyncTask(weight, task, future)
        enqueue(internalTask)
        return future
    }

    /**
     * Submits an asynchronous task with a given weight.
     *
     * The worker counts the weight as soon as the task starts, not when the returned
     * [CompletableFuture] completes.
     *
     * @param weight The weight of the task.
     * @param task A function returning a [CompletableFuture].
     * @return A [CompletableFuture] that completes when the task's future completes.
     */
    fun <T> submitAsyncTask(weight: Int, task: () -> CompletableFuture<T>): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        val internalTask = AsyncTask(weight, task, future)
        enqueue(internalTask)
        return future
    }

    private fun enqueue(task: InternalTask<*>) {
        synchronized(queue) {
            queue.add(task)
        }
        trySchedule()
    }

    private fun trySchedule() {
        if (isProcessing.compareAndSet(false, true)) {
            executor.submit {
                try {
                    processQueue()
                } finally {
                    isProcessing.set(false)
                }
            }
        }
    }

    private fun processQueue() {
        while (true) {
            val task = synchronized(queue) {
                val head = queue.peek() ?: return
                if (currentWeight.get() + head.weight <= limit) {
                    currentWeight.addAndGet(head.weight)
                    queue.poll()
                } else {
                    null
                }
            } ?: break

            task.run()
        }
    }

    private fun scheduleNextReset() {
        val nextReset = reset.nextExecution(triggerContext)
        if (nextReset != null) {
            val now = Instant.now()
            val delay = if (nextReset.isBefore(now)) 0L else nextReset.toEpochMilli() - now.toEpochMilli()
            executor.schedule({
                resetLimit()
                triggerContext.update(nextReset, null, null) // Simple approximation of execution times
                scheduleNextReset()
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun resetLimit() {
        currentWeight.set(0)
        trySchedule()
    }

    private interface InternalTask<T> {
        val weight: Int
        val future: CompletableFuture<T>
        fun run()
    }

    private inner class SyncTask<T>(
        override val weight: Int,
        val action: () -> T,
        override val future: CompletableFuture<T>
    ) : InternalTask<T> {
        override fun run() {
            try {
                future.complete(action())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
    }

    private inner class AsyncTask<T>(
        override val weight: Int,
        val action: () -> CompletableFuture<T>,
        override val future: CompletableFuture<T>
    ) : InternalTask<T> {
        override fun run() {
            try {
                action().whenComplete { result, throwable ->
                    if (throwable != null) {
                        future.completeExceptionally(throwable)
                    } else {
                        future.complete(result)
                    }
                }
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
        }
    }
}