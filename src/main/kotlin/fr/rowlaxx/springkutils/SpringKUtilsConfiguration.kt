
package fr.rowlaxx.springkutils

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

@Configuration
@ComponentScan(basePackageClasses = [SpringKUtilsConfiguration::class])
class SpringKUtilsConfiguration {

    @PostConstruct
    fun init() {
        log.info("Spring K Utils Initialized")
    }

}