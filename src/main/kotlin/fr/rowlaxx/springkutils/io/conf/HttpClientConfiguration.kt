package fr.rowlaxx.springkutils.io.conf

import fr.rowlaxx.springkutils.concurrent.config.GlobalExecutorsConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class HttpClientConfiguration(
    private val globalExecutorsConfiguration: GlobalExecutorsConfiguration
) {
    
    @Bean
    fun httpClient(): HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .executor(globalExecutorsConfiguration.ioExec)
        .build()
    
}