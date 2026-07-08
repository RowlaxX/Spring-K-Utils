package fr.rowlaxx.springkutils.concurrent.core

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TaskQueue(
    dispatcher: CoroutineDispatcher,
    paused: Boolean = false
) {
    private class TaskHolder<T>(
        @JvmField val task: suspend () -> T,
        @JvmField val deferred: CompletableDeferred<T>
    )

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val channel = Channel<TaskHolder<*>>(MAX_PENDING_TASKS)

    private val pending = AtomicInteger(0)
    private val saturated = AtomicBoolean(false)
    private val droppedWhileSaturated = AtomicLong(0)

    private val isPaused = MutableStateFlow(paused)
    private val isClosed = MutableStateFlow(false)
    private val resumeGate: Flow<Boolean> = combine(isPaused, isClosed) { paused, closed -> !paused || closed }

    private val loop: Job

    init {
        loop = scope.launch {
            for (holder in channel) {
                pending.decrementAndGet()
                if (isPaused.value && !isClosed.value) {
                    resumeGate.first { it }
                }

                if (isClosed.value && isPaused.value) {
                    holder.deferred.completeExceptionally(CancellationException("TaskQueue closed while paused. Task aborted."))
                    continue
                }

                if (!holder.deferred.isCancelled) {
                    @Suppress("UNCHECKED_CAST")
                    val typed = holder as TaskHolder<Any?>
                    runCatching { typed.task() }
                        .onFailure { log.error("An error has occurred in TaskQueue", it) }
                        .let { typed.deferred.completeWith(it) }
                }
            }
        }
    }

    fun <T> enqueue(task: suspend () -> T): Deferred<T> {
        val deferred = CompletableDeferred<T>()

        if (isClosed.value) {
            deferred.completeExceptionally(CancellationException("TaskQueue closed"))
            return deferred
        }

        pending.incrementAndGet()
        val result = channel.trySend(TaskHolder(task, deferred))

        if (result.isFailure) {
            pending.decrementAndGet()
            if (result.isClosed) {
                deferred.completeExceptionally(CancellationException("TaskQueue closed"))
            } else {
                // Buffer full: the queue is saturated. Reject the task rather than let it accumulate.
                droppedWhileSaturated.incrementAndGet()
                if (saturated.compareAndSet(false, true)) {
                    log.warn(
                        "TaskQueue saturated at {} pending tasks; rejecting new tasks until it drains",
                        MAX_PENDING_TASKS,
                    )
                }
                deferred.completeExceptionally(
                    RejectedExecutionException("TaskQueue saturated: more than $MAX_PENDING_TASKS tasks pending. Task dropped.")
                )
            }
        } else if (saturated.get() && pending.get() <= LOW_WATER_TASKS && saturated.compareAndSet(true, false)) {
            log.warn("TaskQueue recovered; dropped {} task(s) while saturated", droppedWhileSaturated.getAndSet(0))
        }

        return deferred
    }

    fun submit(task: suspend () -> Unit): Job {
        return enqueue(task)
    }

    fun pause() { isPaused.value = true }
    fun resume() { isPaused.value = false }

    fun close() {
        if (isClosed.compareAndSet(expect = false, update = true)) {
            channel.close()
        }
    }

    suspend fun join() {
        loop.join()
    }

    companion object {
        internal const val MAX_PENDING_TASKS = 8_196
        private const val LOW_WATER_TASKS = MAX_PENDING_TASKS / 2
    }
}