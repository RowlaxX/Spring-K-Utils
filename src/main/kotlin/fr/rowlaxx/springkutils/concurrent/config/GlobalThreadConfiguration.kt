package fr.rowlaxx.springkutils.concurrent.config

import fr.rowlaxx.springkutils.concurrent.core.CountedThreadFactory
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import io.netty.channel.MultiThreadIoEventLoopGroup
import io.netty.channel.nio.NioIoHandler
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
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
import java.util.concurrent.atomic.AtomicLong
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
    val maxQueuedTasks = 10_000

    private val schedulerTasksDropped = AtomicLong(0)
    private val inlineRunFallbacks = AtomicLong(0)

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
    
    private fun saturationPolicy(ownThreadPrefix: String) = RejectedExecutionHandler { task, executor ->
        if (executor.isShutdown) {
            throw RejectedExecutionException("Executor has been shut down; task rejected")
        }

        val caller = Thread.currentThread().name
        when {
            caller == SCHEDULER_THREAD_NAME -> {
                val dropped = schedulerTasksDropped.incrementAndGet()
                if (dropped == 1L || dropped % 10_000 == 0L) {
                    log.warn("Worker pool saturated; dropped {} scheduler task(s) so far (each retries on its next tick)", dropped)
                }
            }
            caller.startsWith("$ownThreadPrefix ") -> {
                val ran = inlineRunFallbacks.incrementAndGet()
                if (ran == 1L || ran % 10_000 == 0L) {
                    log.warn("Worker pool '{}' saturated by its own worker(s); ran {} task(s) inline to avoid self-deadlock", ownThreadPrefix, ran)
                }
                task.run()
            }
            else -> try {
                executor.queue.put(task)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw RejectedExecutionException("Interrupted while waiting to enqueue task", e)
            }
        }
    }

    private fun newBoundedPool(parallelism: Int, threadName: String) = ThreadPoolExecutor(
        parallelism,
        parallelism,
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(maxQueuedTasks),
        CountedThreadFactory(threadName, globalExceptionHandler),
        saturationPolicy(threadName),
    )

    val asyncPool = newBoundedPool(asyncParallelism, ASYNC_THREAD_NAME)

    val ioPool = newBoundedPool(ioParallelism, IO_THREAD_NAME)

    val asyncExec = ConcurrentTaskExecutor(asyncPool).also {
        it.setTaskDecorator(taskDecorator)
    }

    val ioExec = ConcurrentTaskExecutor(ioPool).also {
        it.setTaskDecorator(taskDecorator)
    }

    val taskScheduler = ThreadPoolTaskScheduler().also {
        it.poolSize = schedulerPoolSize
        it.setTaskDecorator(schedulerDecorator)
        it.isRemoveOnCancelPolicy = true
        it.setThreadFactory { task -> Thread(task, SCHEDULER_THREAD_NAME).also { t ->
            t.uncaughtExceptionHandler = globalExceptionHandler
        }}
        it.initialize()
    }

    val ioDispatcher = ioExec.asCoroutineDispatcher()
    val asyncDispatcher = asyncExec.asCoroutineDispatcher()


    val ioEventLoopGroup = MultiThreadIoEventLoopGroup(
        ioEventLoopSize,
        ThreadFactory { runnable -> Thread(runnable, "IO").apply { isDaemon = true } },
        NioIoHandler.newFactory(),
    )


    @PreDestroy
    fun destroy() {
        log.info("Closing threads")
        ioDispatcher.cancel()
        asyncDispatcher.cancel()
        ioPool.shutdown()
        asyncPool.shutdown()
        taskScheduler.shutdown()
        ioEventLoopGroup.shutdownGracefully()
    }

    @Bean
    fun configureTasks() = taskScheduler

    companion object {
        const val SCHEDULER_THREAD_NAME = "Scheduler"
        const val ASYNC_THREAD_NAME = "Core"
        const val IO_THREAD_NAME = "HTTP/WS"
    }

}