package fr.rowlaxx.springkutils.event

import fr.rowlaxx.springkutils.concurrent.annotation.PreventConcurrentExecution
import fr.rowlaxx.springkutils.concurrent.aspect.PreventConcurrentExecutionAspect
import fr.rowlaxx.springkutils.concurrent.config.AsyncConfiguration
import fr.rowlaxx.springkutils.concurrent.config.GlobalExecutorsConfiguration
import fr.rowlaxx.springkutils.event.annotation.Blocking
import fr.rowlaxx.springkutils.event.component.FastEventPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Import
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// --- events (unique top-level names within this package) ---
data class ItBlockingEvent(val value: Int)
data class ItAsyncEvent(val value: Int)
data class ItGuardedEvent(val value: Int)

/**
 * A plain (never-proxied) bean the proxied beans below write their observations into. Reading state
 * back off a CGLIB-proxied bean is unreliable in Kotlin (final property getters can't be overridden
 * by the proxy), so recording goes through this instead.
 */
class ItRecorder {
    val guardedHits = AtomicInteger()
    val asyncMethodLatch = CountDownLatch(1)
    val asyncMethodThread = AtomicReference<String>()
}

/** Sink with one blocking and one async listener; not proxied, so its own fields are read directly. */
class ItEventSink {
    val asyncLatch = CountDownLatch(1)
    val asyncThread = AtomicReference<String>()
    val blockingThread = AtomicReference<String>()

    @Blocking
    @EventListener
    fun onBlocking(event: ItBlockingEvent) {
        blockingThread.set(Thread.currentThread().name)
    }

    @EventListener
    fun onAsync(event: ItAsyncEvent) {
        asyncThread.set(Thread.currentThread().name)
        asyncLatch.countDown()
    }
}

/** Bean made into a real AOP proxy by [PreventConcurrentExecutionAspect], also a blocking listener. */
open class ItGuardedListener(private val recorder: ItRecorder) {
    @PreventConcurrentExecution
    open fun guardedOp() {
        // present only so the real aspect proxies this bean
    }

    @Blocking
    @EventListener
    fun onGuarded(event: ItGuardedEvent) {
        recorder.guardedHits.incrementAndGet()
    }
}

/** A genuine Spring `@Async` bean, to prove FastEventPublisher coexists with @EnableAsync. */
open class ItAsyncBean(private val recorder: ItRecorder) {
    @Async
    open fun runAsync() {
        recorder.asyncMethodThread.set(Thread.currentThread().name)
        recorder.asyncMethodLatch.countDown()
    }
}

/**
 * Integration test that wires [FastEventPublisher] together with the **real** Spring-K-Utils
 * concurrency/async infrastructure — the actual `@Component @Primary` publisher, the real
 * [GlobalExecutorsConfiguration] pools, [AsyncConfiguration]'s `AsyncConfigurer`, `@EnableAsync`, and
 * the real [PreventConcurrentExecutionAspect] — by importing the production classes (rather than the
 * library's broad `@ComponentScan`, which would also slurp sibling test fixtures).
 */
@SpringBootTest(classes = [FastEventPublisherWiringIntegrationTest.WiringConfig::class])
class FastEventPublisherWiringIntegrationTest {

    @Autowired private lateinit var publisher: ApplicationEventPublisher
    @Autowired private lateinit var fastEventPublisher: FastEventPublisher
    @Autowired private lateinit var sink: ItEventSink
    @Autowired private lateinit var guarded: ItGuardedListener
    @Autowired private lateinit var asyncBean: ItAsyncBean
    @Autowired private lateinit var recorder: ItRecorder

    @Test
    fun `the real FastEventPublisher component is wired as the primary publisher`() {
        // The class-level @Primary on the real @Component (not a @Bean override) must win injection.
        assertTrue(publisher is FastEventPublisher)
        assertSame(fastEventPublisher, publisher)
    }

    @Test
    fun `blocking and async listeners dispatch on the real shared pool`() {
        val caller = Thread.currentThread().name
        publisher.publishEvent(ItBlockingEvent(1))
        publisher.publishEvent(ItAsyncEvent(2))

        // Blocking ran inline on the caller.
        assertEquals(caller, sink.blockingThread.get())
        // Async ran on a "Core " worker of the real GlobalExecutorsConfiguration pool.
        assertTrue(sink.asyncLatch.await(5, TimeUnit.SECONDS), "async listener did not fire")
        assertTrue(
            sink.asyncThread.get().startsWith("Core "),
            "async listener ran on '${sink.asyncThread.get()}', expected a 'Core ' pool thread",
        )
    }

    @Test
    fun `FastEventPublisher coexists with spring @Async on the same executor`() {
        asyncBean.runAsync()
        assertTrue(recorder.asyncMethodLatch.await(5, TimeUnit.SECONDS), "@Async method did not run")
        assertTrue(
            recorder.asyncMethodThread.get().startsWith("Core "),
            "@Async ran on '${recorder.asyncMethodThread.get()}', expected the shared 'Core ' pool",
        )
    }

    @Test
    fun `a listener on a bean proxied by the real aspect still receives events`() {
        assertTrue(AopUtils.isAopProxy(guarded), "ItGuardedListener should be proxied by the real aspect")
        publisher.publishEvent(ItGuardedEvent(1)) // @Blocking -> delivered synchronously
        assertEquals(1, recorder.guardedHits.get())
    }

    @Configuration
    @EnableAsync
    @EnableScheduling
    @EnableAspectJAutoProxy
    @Import(
        GlobalExecutorsConfiguration::class,
        AsyncConfiguration::class,
        FastEventPublisher::class,
        PreventConcurrentExecutionAspect::class,
    )
    open class WiringConfig {
        @Bean
        open fun itRecorder() = ItRecorder()

        @Bean
        open fun itEventSink() = ItEventSink()

        @Bean
        open fun itGuardedListener(recorder: ItRecorder) = ItGuardedListener(recorder)

        @Bean
        open fun itAsyncBean(recorder: ItRecorder) = ItAsyncBean(recorder)
    }
}
