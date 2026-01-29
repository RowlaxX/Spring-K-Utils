
package fr.rowlaxx.springkutils

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import jakarta.annotation.PostConstruct
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@EnableAsync
@EnableScheduling
@ComponentScan(basePackageClasses = [SpringKUtilsConfiguration::class])
class SpringKUtilsConfiguration {


}