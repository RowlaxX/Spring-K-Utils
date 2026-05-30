package fr.rowlaxx.springkutils.io.service

import fr.rowlaxx.springkutils.concurrent.config.ThreadConfiguration
import org.springframework.stereotype.Service
import java.net.http.HttpClient
import java.time.Duration

@Service
class HttpClientService(
    private val threadConfiguration: ThreadConfiguration
) {

    val client = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .executor(threadConfiguration.ioExec)
        .build()

}