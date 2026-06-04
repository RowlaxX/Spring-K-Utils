package fr.rowlaxx.springkutils.concurrent.config

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.system.measureTimeMillis

@Configuration
class GlobalExecutorsConfiguration {
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

    private val performanceDecorator: TaskDecorator = TaskDecorator {
        Runnable {
            val millis = measureTimeMillis {
                taskDecorator.decorate(it).run()
            }

            if (millis > 250) {
                log.warn("Scheduler took {} millis for a function.", millis)
            }
        }
    }

    private inner class NamedForkJoinThreadFactory(private val prefix: String) : ForkJoinPool.ForkJoinWorkerThreadFactory {
        private val counter = AtomicInteger(1)

        override fun newThread(pool: ForkJoinPool): ForkJoinWorkerThread {
            val thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool)
            thread.name = "$prefix${counter.getAndIncrement()}"
            thread.uncaughtExceptionHandler = globalExceptionHandler // Attach here
            return thread
        }
    }

    val asyncPool = ForkJoinPool(
        max(1, Runtime.getRuntime().availableProcessors() - 1 - ioParallelism),
        NamedForkJoinThreadFactory("Core "),
        globalExceptionHandler, // 3. (Optional) Also set as the pool's default handler
        true
    )

    val ioPool = ForkJoinPool(
        ioParallelism,
        NamedForkJoinThreadFactory("HTTP/WS "),
        globalExceptionHandler, // 3. (Optional) Also set as the pool's default handler
        true
    )

    val asyncExec = ConcurrentTaskExecutor(asyncPool).also {
        it.setTaskDecorator(taskDecorator)
    }

    val ioExec = ConcurrentTaskExecutor(ioPool).also {
        it.setTaskDecorator(taskDecorator)
    }

    val taskScheduler = ThreadPoolTaskScheduler().also { it ->
        it.poolSize = 1
        it.setTaskDecorator(performanceDecorator)
        it.setThreadFactory { task -> Thread(task, "Scheduler").also { t ->
            t.uncaughtExceptionHandler = globalExceptionHandler
        }}
        it.initialize()
    }

    val ioDispatcher = ioExec.asCoroutineDispatcher()
    val asyncDispatcher = asyncExec.asCoroutineDispatcher()

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