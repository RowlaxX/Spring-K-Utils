package fr.rowlaxx.springkutils.concurrent.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicLong

/**
 * A pool of [SequentialWorker]s, where each worker is associated with a unique key.
 *
 * This pool allows managing multiple [SequentialWorker] instances and automatically
 * flushes (retires and removes) workers that have been idle for a certain period.
 *
 * @property executor The [ExecutorService] used by the workers in this pool.
 */
class SequentialWorkerPool(
    private val executor: ExecutorService,
) {
    private val workers = ConcurrentHashMap<Any, SequentialWorker>()
    private val lastFlushed = AtomicLong(System.currentTimeMillis())

    /**
     * Gets or creates a [SequentialWorker] for the given [workerId].
     *
     * @param workerId The unique identifier for the worker.
     * @return The [SequentialWorker] associated with the [workerId].
     */
    operator fun get(workerId: Any): SequentialWorker {
        tryFlush()
        return workers.computeIfAbsent(workerId) { SequentialWorker(executor) }
    }

    private fun tryFlush() {
        val now = System.currentTimeMillis()
        val last = lastFlushed.get()

        if (now - last > 5000 && lastFlushed.compareAndSet(last, now)) {
            flush()
        }
    }

    internal fun flush() {
        workers.keys.forEach { key ->  workers.computeIfPresent(key) { _, worker ->
            if (worker.pendingTasksCount == 0 && !worker.isRunning) {
                worker.retire()
                null
            }
            else {
                worker
            }
        } }
    }
}