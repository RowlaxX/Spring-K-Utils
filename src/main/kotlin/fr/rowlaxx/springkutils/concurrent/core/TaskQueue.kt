package fr.rowlaxx.springkutils.concurrent.core

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

class TaskQueue(
    dispatcher: CoroutineDispatcher,
    paused: Boolean = false
) {
    private class TaskHolder<T>(
        val task: suspend () -> T,
        val deferred: CompletableDeferred<T>
    )

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val channel = Channel<TaskHolder<*>>(Channel.UNLIMITED)

    private val isPaused = MutableStateFlow(paused)
    private val isClosed = MutableStateFlow(false)

    private val loop: Job

    init {
        loop = scope.launch {
            for (holder in channel) {
                combine(isPaused, isClosed) { b1, b2 -> !b1 || b2 }.first { it }

                if (isClosed.value && isPaused.value) {
                    holder.deferred.completeExceptionally(CancellationException("TaskQueue closed while paused. Task aborted."))
                    continue
                }

                if (!holder.deferred.isCancelled) {
                    run(holder)
                }
            }
        }
    }

    private suspend fun <T> run(holder: TaskHolder<T>) {
        runCatching { holder.task() }
            .onFailure {
                if (it !is CancellationException) {
                    log.error("An error has occurred in TaskQueue", it)
                }
            }
            .let { holder.deferred.completeWith(it) }
    }

    fun <T> enqueue(task: suspend () -> T): Deferred<T> {
        val deferred = CompletableDeferred<T>()

        if (isClosed.value) {
            deferred.completeExceptionally(CancellationException("TaskQueue closed"))
            return deferred
        }

        val result = channel.trySend(TaskHolder(task, deferred))

        if (result.isFailure) {
            deferred.completeExceptionally(CancellationException("TaskQueue closed"))
        }

        return deferred
    }

    fun submit(task: suspend () -> Unit): Job {
        return enqueue { task() }
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
}