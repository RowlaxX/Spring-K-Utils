package fr.rowlaxx.springkutils.scheduling.configuration

import fr.rowlaxx.springkutils.concurrent.core.CountedThreadFactory
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.TaskScheduler
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar

@Configuration
class SchedulingConfiguration {

    @Bean
    fun configureTasks(): TaskScheduler {
        val scheduler = ThreadPoolTaskScheduler()

        scheduler.poolSize = 4
        scheduler.setErrorHandler { log.error("Scheduler error", it) }
        scheduler.setThreadFactory(CountedThreadFactory("Scheduler"))
        scheduler.initialize()

        return scheduler
    }

}