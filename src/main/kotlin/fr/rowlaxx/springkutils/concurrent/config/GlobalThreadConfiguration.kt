package fr.rowlaxx.springkutils.concurrent.config

import fr.rowlaxx.springkutils.concurrent.core.CountedThreadFactory
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import io.netty.channel.nio.NioEventLoopGroup
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

@Configuration
class GlobalThreadConfiguration {
    private val globalExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
        log.error("Uncaught exception dropped to thread-level in ${thread.name}", throwable)
    }

    val schedulerPoolSize = 1
    val ioEventLoopSize = max(2, Runtime.getRuntime().availableProcessors() / 4)
    val ioParallelism = max(2, (Runtime.getRuntime().availableProcessors() / 4))
    val asyncParallelism = max(3, Runtime.getRuntime().availableProcessors() - ioParallelism - schedulerPoolSize - ioEventLoopSize)
    val maxQueuedTasks = 100_000

    private val asyncPoolBacklogWarned = AtomicBoolean(false)

    private val taskDecorator: TaskDecorator = TaskDecorator {
        Runnable {
            try {
                it.run()
            } catch (e: Exception) {
                log.error("Unexpected error occurred", e)
            }
        }
    }

    private val schedulerDecorator: TaskDecorator = TaskDecorator {
        Runnable {
            asyncExec.submit(taskDecorator.decorate(it))
        }
    }

    private val backpressureOnFull = RejectedExecutionHandler { task, executor ->
        if (executor.isShutdown) {
            throw RejectedExecutionException("Executor has been shut down; task rejected")
        }
        try {
            executor.queue.put(task)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RejectedExecutionException("Interrupted while waiting to enqueue task", e)
        }
    }

    private fun newBoundedPool(parallelism: Int, threadName: String) = ThreadPoolExecutor(
        parallelism,
        parallelism,
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(maxQueuedTasks),
        CountedThreadFactory(threadName, globalExceptionHandler),
        backpressureOnFull,
    )

    val asyncPool = newBoundedPool(asyncParallelism, "Core")

    val ioPool = newBoundedPool(ioParallelism, "HTTP/WS")

    val asyncExec = ConcurrentTaskExecutor(asyncPool).also {
        it.setTaskDecorator(taskDecorator)
    }

    val ioExec = ConcurrentTaskExecutor(ioPool).also {
        it.setTaskDecorator(taskDecorator)
    }

    val taskScheduler = ThreadPoolTaskScheduler().also {
        it.poolSize = schedulerPoolSize
        it.setTaskDecorator(schedulerDecorator)
        it.setThreadFactory { task -> Thread(task, "Scheduler").also { t ->
            t.uncaughtExceptionHandler = globalExceptionHandler
        }}
        it.initialize()
    }

    val ioDispatcher = ioExec.asCoroutineDispatcher()
    val asyncDispatcher = asyncExec.asCoroutineDispatcher()


    @Suppress("DEPRECATION")
    val ioEventLoopGroup = NioEventLoopGroup(ioEventLoopSize, ThreadFactory { runnable -> Thread(runnable, "IO").apply { isDaemon = true } })


    @PreDestroy
    fun destroy() {
        log.info("Closing threads")
        ioPool.shutdown()
        asyncPool.shutdown()
        taskScheduler.shutdown()
        ioEventLoopGroup.shutdownGracefully()
    }

    @Bean
    fun configureTasks() = taskScheduler

}