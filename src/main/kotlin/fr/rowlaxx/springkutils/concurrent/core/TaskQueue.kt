package fr.rowlaxx.springkutils.concurrent.core

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TaskQueue(
    dispatcher: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val mutex = Mutex()
    private var lastTask: Job? = null

    fun enqueue(task: suspend () -> Unit) {
        scope.launch { mutex.withLock {
            lastTask = lastTask.let { last -> scope.launch {
                last?.join()
                task()
            }}
        }}
    }

    fun close() {
        scope.cancel()
    }
}