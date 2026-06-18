package fr.rowlaxx.springkutils.io.conf

import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.EventLoopGroup
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.DefaultAsyncHttpClientConfig
import org.asynchttpclient.Dsl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * The single shared Netty-backed [AsyncHttpClient] used for BOTH REST calls and client WebSockets.
 *
 * All connections multiplex over ONE dedicated NIO event-loop thread (named "IO") — hundreds of
 * sockets, no thread-per-socket — with pooled read buffers (which avoids the per-chunk TLS buffer
 * churn of `java.net.http`). That one thread is carved out of the io budget: `GlobalExecutorsConfiguration`
 * gives its `ioPool` ForkJoinPool one core less, so the total thread count is unchanged. (Netty event
 * loops are permanent `select()` loops and cannot run on that ForkJoinPool directly — it also runs
 * deserialization and would starve.)
 *
 * The client-wide request/read timeouts are disabled because WebSockets are long-lived and their
 * liveness is owned elsewhere; REST callers set a finite per-request timeout via
 * `BoundRequestBuilder.setRequestTimeout`. Keep response handling OFF this thread (offload to a worker
 * pool) so a slow parse never stalls socket I/O.
 */
@Configuration
class HttpClientConfiguration(
    val thread: GlobalThreadConfiguration
) {

    @Bean(destroyMethod = "close")
    fun httpClient(): AsyncHttpClient = Dsl.asyncHttpClient(
        DefaultAsyncHttpClientConfig.Builder()
            .setEventLoopGroup(thread.ioEventLoopGroup)
            .setConnectTimeout(Duration.ofSeconds(15))
            .setRequestTimeout(Duration.ofSeconds(15))
            .setReadTimeout(Duration.ofSeconds(15))
            .setShutdownTimeout(Duration.ofSeconds(15))
            .setTcpNoDelay(true)
            .setUseNativeTransport(false)
            .setAllocator(PooledByteBufAllocator.DEFAULT)
            .setWebSocketMaxFrameSize(16 * 1024 * 1024)
            .build()
    )
}
