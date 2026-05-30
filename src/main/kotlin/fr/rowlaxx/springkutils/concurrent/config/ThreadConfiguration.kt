package fr.rowlaxx.springkutils.concurrent.config

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import kotlin.math.max
import kotlin.math.min

@Configuration
class ThreadConfiguration : AsyncConfigurer {
    val ioParallelism = max(1, min(4, Runtime.getRuntime().availableProcessors() / 4))

    private val taskDecorator: TaskDecorator = TaskDecorator {
        Runnable {
            try {
                it.run()
            } catch (e: Exception) {
                log.error("Unexpected error occurred", e)
            }
        }
    }

    val asyncExec = ThreadPoolTaskExecutor().also {
        val proc = Runtime.getRuntime().availableProcessors()

        it.corePoolSize = proc - 1 - ioParallelism
        it.maxPoolSize = proc - 1 - ioParallelism
        it.setThreadNamePrefix("Core ")
        it.setTaskDecorator(taskDecorator)
        it.initialize()
    }

    val taskScheduler = ThreadPoolTaskScheduler().also {
        it.poolSize = 1
        it.setErrorHandler { error -> log.error("Scheduler error", error) }
        it.setThreadFactory { task -> Thread(task, "Scheduler") }
        it.initialize()
    }

    val ioExec = ThreadPoolTaskExecutor().also {
        it.corePoolSize = ioParallelism
        it.maxPoolSize = ioParallelism
        it.setTaskDecorator(taskDecorator)
        it.setThreadNamePrefix("HTTP/WS ")
        it.initialize()
    }

    val ioDispatcher = ioExec.asCoroutineDispatcher()

    @PreDestroy
    fun destroy() {
        ioExec.shutdown()
    }

    @Bean
    fun configureTasks() = taskScheduler

    override fun getAsyncExecutor() = asyncExec

}