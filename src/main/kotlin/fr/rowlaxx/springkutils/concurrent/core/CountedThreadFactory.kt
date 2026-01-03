package fr.rowlaxx.springkutils.concurrent.core

import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [ThreadFactory] that creates threads with a specific name followed by an incrementing counter.
 *
 * @property name the prefix name for the threads created by this factory.
 */
class CountedThreadFactory(
    val name: String
) : ThreadFactory {
    private val counter = AtomicInteger()

    override fun newThread(r: Runnable): Thread {
        return Thread(r, "$name ${counter.incrementAndGet()}")
    }
}