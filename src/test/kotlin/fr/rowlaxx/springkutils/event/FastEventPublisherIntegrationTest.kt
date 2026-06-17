package fr.rowlaxx.springkutils.event

import fr.rowlaxx.springkutils.concurrent.config.GlobalExecutorsConfiguration
import fr.rowlaxx.springkutils.event.annotation.Blocking
import fr.rowlaxx.springkutils.event.component.FastEventPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.annotation.Primary
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.context.event.EventListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// --- events (unique top-level names to avoid clashing with other test files in this package) ---
data class IntgStartEvent(val value: String)
data class IntgMidEvent(val value: String)
data class IntgEndEvent(val value: String, val thread: String)
data class IntgBoomEvent(val value: Int)
data class IntgPingEvent(val value: Int)
data class IntgBlockingBoomEvent(val value: Int)
data class IntgInheritedEvent(val value: Int)

/** Two-hop async pipeline that republishes through the injected publisher, mimicking the real app. */
class IntgPipeline(private val publisher: ApplicationEventPublisher) {
    @EventListener
    fun onStart(event: IntgStartEvent) {
        publisher.publishEvent(IntgMidEvent("${event.value}:mid"))
    }

    @EventListener
    fun onMid(event: IntgMidEvent) {
        publisher.publishEvent(IntgEndEvent("${event.value}:end", Thread.currentThread().name))
    }
}

/** Terminal sink for the pipeline. */
class IntgSink {
    val endLatch = CountDownLatch(1)
    val endValue = AtomicReference<String>()
    val endThread = AtomicReference<String>()

    @EventListener
    fun onEnd(event: IntgEndEvent) {
        endValue.set(event.value)
        endThread.set(event.thread)
        endLatch.countDown()
    }
}

/** Async failure isolation + blocking failure propagation. */
class IntgFailureListeners {
    val pingLatch = CountDownLatch(1)

    @EventListener
    fun onBoom(event: IntgBoomEvent): Unit = throw RuntimeException("async boom (expected)")

    @EventListener
    fun onPing(event: IntgPingEvent) {
        pingLatch.countDown()
    }

    @Blocking
    @EventListener
    fun onBlockingBoom(event: IntgBlockingBoomEvent): Unit =
        throw IllegalStateException("blocking boom (expected)")
}

/** @EventListener declared on a base class; the bean is a concrete subclass. */
abstract class IntgBaseListener {
    val inheritedHits = AtomicInteger()

    @Blocking
    @EventListener
    open fun onInherited(event: IntgInheritedEvent) {
        inheritedHits.incrementAndGet()
    }
}

class IntgConcreteListener : IntgBaseListener()

/** Listens to a Spring framework lifecycle event to prove those still flow through Spring. */
class IntgLifecycleListener {
    val refreshCount = AtomicInteger()

    @EventListener
    fun onRefresh(event: ContextRefreshedEvent) {
        refreshCount.incrementAndGet()
    }
}

/** Confirms @Primary causes constructor-injected publishers to receive the FastEventPublisher. */
class IntgPublisherProbe(val publisher: ApplicationEventPublisher)

@SpringBootTest(classes = [FastEventPublisherIntegrationTest.TestConfig::class])
class FastEventPublisherIntegrationTest {

    @Autowired private lateinit var publisher: ApplicationEventPublisher
    @Autowired private lateinit var fastEventPublisher: FastEventPublisher
    @Autowired private lateinit var sink: IntgSink
    @Autowired private lateinit var failures: IntgFailureListeners
    @Autowired private lateinit var inherited: IntgConcreteListener
    @Autowired private lateinit var lifecycle: IntgLifecycleListener
    @Autowired private lateinit var probe: IntgPublisherProbe

    @Test
    fun `primary publisher is injected everywhere in a Spring Boot context`() {
        assertTrue(publisher is FastEventPublisher, "injected publisher should be the FastEventPublisher")
        assertSame(fastEventPublisher, publisher)
        assertSame(fastEventPublisher, probe.publisher)
    }

    @Test
    fun `multi-hop async pipeline completes on the async pool`() {
        publisher.publishEvent(IntgStartEvent("seed"))

        assertTrue(sink.endLatch.await(5, TimeUnit.SECONDS), "pipeline did not complete in time")
        assertEquals("seed:mid:end", sink.endValue.get())
        assertTrue(
            sink.endThread.get().startsWith("Core "),
            "pipeline hops should run on the async pool, last ran on '${sink.endThread.get()}'",
        )
    }

    @Test
    fun `a throwing async listener is isolated and does not break the pool`() {
        publisher.publishEvent(IntgBoomEvent(1)) // throws inside the async task, must be swallowed
        publisher.publishEvent(IntgPingEvent(2)) // a subsequent, independent dispatch must still run

        assertTrue(
            failures.pingLatch.await(5, TimeUnit.SECONDS),
            "async pool died after a listener threw — subsequent dispatch was lost",
        )
    }

    @Test
    fun `a throwing blocking listener propagates to the caller`() {
        assertThrows(IllegalStateException::class.java) {
            publisher.publishEvent(IntgBlockingBoomEvent(1))
        }
    }

    @Test
    fun `a listener declared on a base class is invoked`() {
        publisher.publishEvent(IntgInheritedEvent(1)) // @Blocking -> delivered synchronously
        assertEquals(1, inherited.inheritedHits.get())
    }

    @Test
    fun `spring lifecycle events still fire exactly once through spring's multicaster`() {
        // ContextRefreshedEvent is published by Spring (not via the injected publisher) during startup.
        assertEquals(1, lifecycle.refreshCount.get())
    }

    @Configuration
    @EnableAspectJAutoProxy
    open class TestConfig {
        @Bean
        open fun executors() = GlobalExecutorsConfiguration()

        @Bean
        @Primary
        open fun fastEventPublisher(
            ctx: org.springframework.context.ApplicationContext,
            executors: GlobalExecutorsConfiguration,
        ) = FastEventPublisher(ctx, executors)

        @Bean
        open fun pipeline(publisher: ApplicationEventPublisher) = IntgPipeline(publisher)

        @Bean
        open fun sink() = IntgSink()

        @Bean
        open fun failureListeners() = IntgFailureListeners()

        @Bean
        open fun inheritedListener() = IntgConcreteListener()

        @Bean
        open fun lifecycleListener() = IntgLifecycleListener()

        @Bean
        open fun publisherProbe(publisher: ApplicationEventPublisher) = IntgPublisherProbe(publisher)
    }
}
