package fr.rowlaxx.springkutils.concurrent.core

import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.onCancelled
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.onError
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A worker that executes tasks sequentially in the provided [ExecutorService].
 *
 * The [SequentialWorker] ensures that only one task is running at a time, even if multiple
 * tasks are submitted from different threads. It supports both synchronous and asynchronous tasks.
 *
 * For asynchronous tasks ([submitAsyncTask]), the worker waits for the returned [CompletableFuture]
 * to complete before picking up the next task from the queue.
 *
 * Tasks can be cancelled by calling [CompletableFuture.cancel] on the future returned by the submission
 * methods. If a task is cancelled before it starts, it will be skipped. If an asynchronous task is
 * cancelled while it is running, the underlying future returned by the task will also be cancelled.
 *
 * This class is thread-safe.
 *
 * @property executor The [ExecutorService] used to run tasks.
 */
class SequentialWorker(
    private val executor: ExecutorService,
) {
    private val queue = ConcurrentLinkedQueue<InternalTask<*>>()
    private val processing = AtomicBoolean(false)
    private val retired = AtomicBoolean(false)
    
    /**
     * True if the worker is currently processing a task.
     */
    val isRunning: Boolean get() = processing.get()

    /**
     * True if the worker has been retired. A retired worker does not accept new tasks
     * and will complete pending tasks exceptionally.
     */
    val isRetired: Boolean get() = retired.get()

    /**
     * The number of tasks currently waiting in the queue.
     */
    val pendingTasksCount: Int get() = queue.size

    /**
     * Retires the worker. 
     * 
     * Once retired:
     * - New tasks submitted via [submitTask] or [submitAsyncTask] will fail immediately.
     * - Pending tasks in the queue will be completed exceptionally with an [IllegalStateException].
     * - If a task is currently running, it will continue but no further tasks will be started.
     */
    fun retire() {
        retired.set(true)

        while (!queue.isEmpty()) {
            val task = queue.poll() ?: continue
            task.future.cancel(false)
        }
    }
    
    /**
     * Submits a synchronous task for sequential execution.
     *
     * If the worker is retired, the returned [CompletableFuture] will be immediately exceptionally completed with a
     * [IllegalStateException]
     *
     * The task can be cancelled via the returned [CompletableFuture]. If cancelled before execution,
     * the task will be removed from the queue and not executed.
     *
     * @param task The task to execute.
     * @return A [CompletableFuture] that completes when the task is finished.
     */
    fun <T> submitTask(task: () -> T): CompletableFuture<T> {
        if (isRetired) {
            return CompletableFuture.failedFuture(IllegalStateException("Worker has been retired"))    
        }
        
        val future = CompletableFuture<T>()
        val task = SyncTask(task, future)

        future.onCancelled { queue.remove(task) }
        queue.add(task)
        trySchedule()

        return future
    }

    /**
     * Submits an asynchronous task for sequential execution.
     * 
     * The worker will wait for the [CompletableFuture] returned by the [task] to complete
     * before starting the next task.
     *
     * If the worker is retired, the returned [CompletableFuture] will be immediately exceptionally completed with a
     * [IllegalStateException]
     *
     * The task can be cancelled via the returned [CompletableFuture]. If cancelled before execution,
     * it will be skipped. If cancelled while running, the future returned by the [task] will be cancelled.
     *
     * @param task A function returning a [CompletableFuture].
     * @return A [CompletableFuture] that completes when the task's future completes.
     */
    fun <T> submitAsyncTask(task: () -> CompletableFuture<T>): CompletableFuture<T> {
        if (isRetired) {
            return CompletableFuture.failedFuture(IllegalStateException("Worker has been retired"))
        }
        
        val future = CompletableFuture<T>()
        val task = AsyncTask(task, future)

        future.onCancelled { queue.remove(task) }
        queue.add(task)
        trySchedule()

        return future
    }

    /**
     * Executes the task only if the worker is currently idle.
     * 
     * @param task The task to execute.
     * @return A [CompletableFuture] that completes with the task result, or fails with [IllegalStateException] if the worker is busy or retired.
     */
    fun <T> runTaskIfIdle(task: () -> T): CompletableFuture<T> {
        if (isRunning) {
            return CompletableFuture.failedFuture(IllegalStateException("Worker is busy"))
        }

        return submitTask(task)
    }

    /**
     * Executes the asynchronous task only if the worker is currently idle.
     * 
     * @param task A function returning a [CompletableFuture].
     * @return A [CompletableFuture] that completes with the task result, or fails with [IllegalStateException] if the worker is busy or retired.
     */
    fun <T> runAsyncTaskIfIdle(task: () -> CompletableFuture<T>): CompletableFuture<T> {
        if (isRunning) {
            return CompletableFuture.failedFuture(IllegalStateException("Worker is busy"))
        }

        return submitAsyncTask(task)
    }

    private fun trySchedule(recursive: Boolean = false, executorContext: Boolean = false) {
        if (recursive || processing.compareAndSet(false, true)) {
            val nextTask = queue.poll()

            if (nextTask == null) {
                processing.set(false)
            }
            else if (isRetired) {
                nextTask.future.cancel(false)
                trySchedule(recursive = true)
            }
            else if (nextTask.future.isCompletedExceptionally) {
                trySchedule(recursive = true)
            }
            else {
                if (executorContext) {
                    nextTask.run()
                }
                else {
                    executor.submit(nextTask::run)
                }
            }
        }
    }

    private interface InternalTask<T> {
        val future: CompletableFuture<T>
        
        fun run()
    }
    
    private inner class SyncTask<T>(
        val action: () -> T,
        override val future: CompletableFuture<T>
    ) : InternalTask<T> {
        
        override fun run() {
            try {
                future.complete(action())
            } catch (e: Throwable) {
                future.completeExceptionally(e)
            }
            trySchedule(recursive = true, executorContext = true)
        }
    }
    
    private inner class AsyncTask<T>(
        val action: () -> CompletableFuture<T>,
        override val future: CompletableFuture<T>
    ) : InternalTask<T> {
        override fun run() {
            try {
                val actionFuture = action()
                future.onCancelled { actionFuture.cancel(true) }

                actionFuture.handle { result, throwable ->
                    if (throwable != null) {
                        future.completeExceptionally(throwable)
                    } else {
                        future.complete(result)
                    }
                    trySchedule(recursive = true)
                }
            } catch (e: Throwable) {
                future.completeExceptionally(e)
                trySchedule(recursive = true)
            }
        }
    }
}