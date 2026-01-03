package fr.rowlaxx.springkutils.scheduling.configuration

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.SchedulingConfigurer
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.scheduling.config.ScheduledTaskRegistrar

@Configuration
@ConditionalOnBean(org.springframework.scheduling.annotation.SchedulingConfiguration::class)
class SchedulingConfiguration : SchedulingConfigurer {

    override fun configureTasks(taskRegistrar: ScheduledTaskRegistrar) {
        val scheduler = ThreadPoolTaskScheduler()
        scheduler.poolSize = 4
        scheduler.setErrorHandler { log.error("Scheduler error", it) }
        scheduler.setThreadNamePrefix("Scheduler ")
        scheduler.initialize()

        taskRegistrar.setTaskScheduler(scheduler)
    }

}