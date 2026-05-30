package fr.rowlaxx.springkutils.concurrent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TaskQueue(
    dispatcher: CoroutineDispatcher,
    paused: Boolean = false
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val queueMutex = Mutex()
    private val pauseMutex = Mutex(locked = paused)
    private var lastTask: Job? = null

    var isPaused: Boolean = paused
        private set

    fun enqueue(task: suspend () -> Unit) {
        scope.launch { queueMutex.withLock {
            lastTask = scope.launch {
                lastTask?.join()

                pauseMutex.withLock {
                    task()
                }
            }
        }}
    }

    fun pause() {
        scope.launch { queueMutex.withLock {
            if (!isPaused) {
                pauseMutex.lock() // Lock the gate
                isPaused = true
            }
        } }
    }

    fun resume() {
        scope.launch { queueMutex.withLock {
            if (isPaused) {
                pauseMutex.unlock() // Open the gate
                isPaused = false
            }
        } }
    }

    fun close() {
        scope.cancel()
    }
}