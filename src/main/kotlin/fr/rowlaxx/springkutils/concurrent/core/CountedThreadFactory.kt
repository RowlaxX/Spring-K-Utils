package fr.rowlaxx.springkutils.concurrent.core

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [ThreadFactory] that creates threads with a specific name followed by an incrementing counter.
 *
 * @property name the prefix name for the threads created by this factory.
 * @property uncaughtExceptionHandler optional handler installed on every created thread.
 */
class CountedThreadFactory(
    val name: String,
    private val uncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null,
) : ThreadFactory {
    private val counter = AtomicInteger()

    override fun newThread(r: Runnable): Thread {
        return Thread(r, "$name ${counter.incrementAndGet()}").also { thread ->
            uncaughtExceptionHandler?.let { thread.uncaughtExceptionHandler = it }
        }
    }
}