package fr.rowlaxx.springkutils.concurrent.config

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import io.netty.channel.nio.NioEventLoopGroup
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

@Configuration
class GlobalThreadConfiguration {
    private val globalExceptionHandler = Thread.UncaughtExceptionHandler { thread, throwable ->
        log.error("Uncaught exception dropped to thread-level in ${thread.name}", throwable)
    }

    val ioParallelism = max(1, min(6, Runtime.getRuntime().availableProcessors() / 4))

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

    private inner class NamedForkJoinThreadFactory(private val prefix: String) : ForkJoinPool.ForkJoinWorkerThreadFactory {
        private val counter = AtomicInteger(1)

        override fun newThread(pool: ForkJoinPool): ForkJoinWorkerThread {
            val thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool)
            thread.name = "$prefix${counter.getAndIncrement()}"
            thread.uncaughtExceptionHandler = globalExceptionHandler
            return thread
        }
    }

    val asyncPool = ForkJoinPool(
        max(1, Runtime.getRuntime().availableProcessors() - 1 - ioParallelism),
        NamedForkJoinThreadFactory("Core "),
        globalExceptionHandler,
        true
    )

    val ioPool = ForkJoinPool(
        max(1, ioParallelism - 1),
        NamedForkJoinThreadFactory("HTTP/WS "),
        globalExceptionHandler,
        true
    )

    val asyncExec = ConcurrentTaskExecutor(asyncPool).also {
        it.setTaskDecorator(taskDecorator)
    }

    val ioExec = ConcurrentTaskExecutor(ioPool).also {
        it.setTaskDecorator(taskDecorator)
    }

    val taskScheduler = ThreadPoolTaskScheduler().also {
        it.poolSize = 1
        it.setTaskDecorator(schedulerDecorator)
        it.setThreadFactory { task -> Thread(task, "Scheduler").also { t ->
            t.uncaughtExceptionHandler = globalExceptionHandler
        }}
        it.initialize()
    }

    val ioDispatcher = ioExec.asCoroutineDispatcher()
    val asyncDispatcher = asyncExec.asCoroutineDispatcher()


    @Suppress("DEPRECATION")
    val ioEventLoopGroup = NioEventLoopGroup(1, ThreadFactory { runnable -> Thread(runnable, "IO").apply { isDaemon = true } })


    @PreDestroy
    fun destroy() {
        log.info("Closing threads")
        ioPool.shutdown()
        asyncPool.shutdown()
        taskScheduler.shutdown()
    }

    @Bean
    fun configureTasks() = taskScheduler

}