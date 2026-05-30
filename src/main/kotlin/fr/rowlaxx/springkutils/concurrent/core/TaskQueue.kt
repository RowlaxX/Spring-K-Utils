package fr.rowlaxx.springkutils.concurrent.core

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TaskQueue(
    dispatcher: CoroutineDispatcher,
    paused: Boolean = false
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val queueMutex = Mutex()
    private var lastTask: Job? = null

    private val isPaused = MutableStateFlow(paused)

    fun <T> enqueue(task: suspend () -> T): Deferred<T> {
        val deferred = CompletableDeferred<T>()

        scope.launch { queueMutex.withLock {
            val previous = lastTask

            lastTask = scope.launch {
                previous?.join()
                isPaused.first { !it }

                runCatching { task() }
                    .onFailure { log.error("An error has occurred in TaskQueue", it) }
                    .let { deferred.completeWith(it) }
            }
        }}

        return deferred
    }

    fun submit(task: suspend () -> Unit): Job {
        return enqueue { task() }
    }

    fun pause() { isPaused.value = true }
    fun resume() { isPaused.value = false }

    fun close() {
        scope.cancel()
    }
}