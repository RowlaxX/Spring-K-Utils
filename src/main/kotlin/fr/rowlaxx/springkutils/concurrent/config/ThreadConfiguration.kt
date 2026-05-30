package fr.rowlaxx.springkutils.concurrent.config

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import kotlinx.coroutines.asCoroutineDispatcher
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.math.min

@Configuration
class ThreadConfiguration : AsyncConfigurer {
    val ioParallelism = max(1, min(4, Runtime.getRuntime().availableProcessors() / 4))

    val asyncExecutor = ThreadPoolTaskExecutor().also {
        val proc = Runtime.getRuntime().availableProcessors()

        it.corePoolSize = proc - 1 - ioParallelism
        it.maxPoolSize = proc - 1 - ioParallelism
        it.setThreadNamePrefix("Core ")
        it.initialize()
    }

    val taskScheduler = ThreadPoolTaskScheduler().also {
        it.poolSize = 1
        it.setErrorHandler { error -> log.error("Scheduler error", error) }
        it.setThreadFactory { task -> Thread(task, "Scheduler") }
        it.initialize()
    }

    val ioExecutor = ThreadPoolTaskExecutor().also {
        it.corePoolSize = ioParallelism
        it.maxPoolSize = ioParallelism
        it.setThreadNamePrefix("Core IO ")
        it.initialize()
    }

    val ioDispatcher = ioExecutor.asCoroutineDispatcher()

    @Bean
    fun configureTasks() = taskScheduler

    override fun getAsyncExecutor() = asyncExecutor

    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler? {
        return CustomAsyncExceptionHandler()
    }

    class CustomAsyncExceptionHandler : AsyncUncaughtExceptionHandler {

        override fun handleUncaughtException(throwable: Throwable, method: Method, vararg params: Any?) {
            log.error("Unexpected error occurred in method: {}", method.name, throwable)
        }
    }

}