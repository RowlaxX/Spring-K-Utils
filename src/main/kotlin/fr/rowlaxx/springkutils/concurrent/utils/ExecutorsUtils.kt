package fr.rowlaxx.springkutils.concurrent.utils

import fr.rowlaxx.springkutils.concurrent.core.CountedThreadFactory
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Extension functions and utilities for [java.util.concurrent.Executors].
 */
object ExecutorsUtils {

    /**
     * Creates a new [ScheduledExecutorService] that wraps all tasks with an error logger.
     * This ensures that any exception thrown by a task is logged before being rethrown.
     *
     * @param parallelism the number of threads in the pool.
     * @param name the name of the executor, used for thread naming and logging.
     * @return a [ScheduledExecutorService] that logs exceptions.
     */
    fun newFailsafeScheduledExecutor(parallelism: Int, name: String): ScheduledExecutorService {
        val executor = Executors.newScheduledThreadPool(parallelism, CountedThreadFactory(name))
        return InternalScheduledExecutorServiceProxy(name, executor)
    }

    /**
     * A proxy for [ScheduledExecutorService] that wraps every [Runnable] and [Callable]
     * to log any [Throwable] that occurs during execution.
     */
    private class InternalScheduledExecutorServiceProxy(
        private val name: String,
        private val proxy: ScheduledExecutorService,
    ) : ScheduledExecutorService {
        private fun wrap(runnable: Runnable): Runnable {
            return Runnable {
                try {
                    runnable.run()
                } catch (e: Throwable) {
                    log.error("An exception occurred in the executor {}", name, e)
                    throw e
                }
            }
        }

        private fun <V> wrap(callable: Callable<V>): Callable<V> {
            return Callable {
                try {
                    callable.call()
                } catch (e: Throwable) {
                    log.error("An exception occurred in the executor {}", name, e)
                    throw e
                }
            }
        }

        override fun schedule(
            command: Runnable,
            delay: Long,
            unit: TimeUnit
        ): ScheduledFuture<*> {
            return proxy.schedule(wrap(command), delay, unit)
        }

        override fun <V : Any?> schedule(
            callable: Callable<V?>,
            delay: Long,
            unit: TimeUnit
        ): ScheduledFuture<V?> {
            return proxy.schedule(wrap(callable), delay, unit)
        }

        override fun scheduleAtFixedRate(
            command: Runnable,
            initialDelay: Long,
            period: Long,
            unit: TimeUnit
        ): ScheduledFuture<*> {
            return proxy.scheduleAtFixedRate(wrap(command), initialDelay, period, unit)
        }

        override fun scheduleWithFixedDelay(
            command: Runnable,
            initialDelay: Long,
            delay: Long,
            unit: TimeUnit
        ): ScheduledFuture<*> {
            return proxy.scheduleWithFixedDelay(wrap(command), initialDelay, delay, unit)
        }

        override fun shutdown() {
            proxy.shutdown()
        }

        override fun shutdownNow(): List<Runnable> {
            return proxy.shutdownNow()
        }

        override fun isShutdown(): Boolean {
            return proxy.isShutdown
        }

        override fun isTerminated(): Boolean {
            return proxy.isTerminated
        }

        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean {
            return proxy.awaitTermination(timeout, unit)
        }

        override fun <T : Any?> submit(task: Callable<T?>): Future<T?> {
            return proxy.submit(wrap(task))
        }

        override fun <T : Any?> submit(task: Runnable, result: T?): Future<T?> {
            return proxy.submit(wrap(task), result)
        }

        override fun submit(task: Runnable): Future<*> {
            return proxy.submit(wrap(task))
        }

        override fun <T : Any?> invokeAll(tasks: Collection<Callable<T?>?>): List<Future<T?>?> {
            return proxy.invokeAll(tasks.map { it?.let { wrap(it) } })
        }

        override fun <T : Any?> invokeAll(
            tasks: Collection<Callable<T?>?>,
            timeout: Long,
            unit: TimeUnit
        ): List<Future<T?>?> {
            return proxy.invokeAll(tasks.map { it?.let { wrap(it) } }, timeout, unit)
        }

        override fun <T : Any?> invokeAny(tasks: Collection<Callable<T?>?>): T & Any {
            return proxy.invokeAny(tasks.map { it?.let { wrap(it) } })!!
        }

        override fun <T : Any?> invokeAny(
            tasks: Collection<Callable<T?>?>,
            timeout: Long,
            unit: TimeUnit
        ): T? {
            return proxy.invokeAny(tasks.map { it?.let { wrap(it) } }, timeout, unit)
        }

        override fun execute(command: Runnable) {
            proxy.execute(wrap(command))
        }


    }

}