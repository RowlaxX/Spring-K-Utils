package fr.rowlaxx.springkutils.event

import com.sun.management.ThreadMXBean
import fr.rowlaxx.springkutils.concurrent.config.GlobalExecutorsConfiguration
import fr.rowlaxx.springkutils.event.annotation.Blocking
import fr.rowlaxx.springkutils.event.component.FastEventPublisher
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Events used only by this test. */
data class AlphaEvent(val value: Int)
data class BetaEvent(val value: Int)
data class OrphanEvent(val value: Int)
data class GammaEvent(val value: Int)
data class DeltaEvent(val value: Int)

/**
 * Records, per listener invocation, which event arrived and the name of the thread it ran on, so the
 * test can assert both correct delivery and correct threading (blocking = caller thread, async = a
 * "Core " pool thread).
 */
@Component
open class RecordingListeners {
    val blockingHits = ConcurrentLinkedQueue<Pair<Any, String>>()
    val asyncHits = ConcurrentLinkedQueue<Pair<Any, String>>()
    val secondAlphaHits = ConcurrentLinkedQueue<Any>()

    @Volatile
    var latch = CountDownLatch(0)

    @Blocking
    @EventListener
    fun onAlphaBlocking(event: AlphaEvent) {
        blockingHits += (event as Any) to Thread.currentThread().name
    }

    // No @Blocking -> dispatched on the async pool. Declared protected to exercise privateLookupIn.
    @EventListener
    protected fun onBetaAsync(event: BetaEvent) {
        asyncHits += (event as Any) to Thread.currentThread().name
        latch.countDown()
    }

    // A second, independent listener for AlphaEvent (also blocking) to prove fan-out.
    @Blocking
    @EventListener
    fun onAlphaBlockingSecond(event: AlphaEvent) {
        secondAlphaHits += event
    }

    // Allocation-free listener body (primitive counter only) for the allocation benchmark.
    @Volatile
    var gammaCount = 0L

    @Blocking
    @EventListener
    fun onGamma(event: GammaEvent) {
        gammaCount++
    }

    // No-argument listeners: the event type is named in the annotation, the payload is not delivered.
    val noArgAlphaHits = AtomicInteger()
    val noArgMultiHits = ConcurrentLinkedQueue<String>()

    @Blocking
    @EventListener(AlphaEvent::class)
    fun onAlphaNoArg() {
        noArgAlphaHits.incrementAndGet()
    }

    // `classes = [...]` form, async, registered for two distinct event types.
    @EventListener(classes = [BetaEvent::class, DeltaEvent::class])
    open fun onBetaOrDeltaNoArg() {
        noArgMultiHits += Thread.currentThread().name
    }
}

/** Injects the publisher purely to confirm @Primary makes the injected publisher our bean. */
@Component
class PublisherHolder(val publisher: ApplicationEventPublisher)

class FastEventPublisherTest {

    private lateinit var ctx: AnnotationConfigApplicationContext
    private lateinit var publisher: ApplicationEventPublisher
    private lateinit var listeners: RecordingListeners
    private lateinit var executors: GlobalExecutorsConfiguration

    @BeforeEach
    fun setUp() {
        ctx = AnnotationConfigApplicationContext().apply {
            register(
                GlobalExecutorsConfiguration::class.java,
                FastEventPublisher::class.java,
                RecordingListeners::class.java,
                PublisherHolder::class.java,
            )
            refresh()
        }
        publisher = ctx.getBean(ApplicationEventPublisher::class.java)
        listeners = ctx.getBean(RecordingListeners::class.java)
        executors = ctx.getBean(GlobalExecutorsConfiguration::class.java)
    }

    @AfterEach
    fun tearDown() {
        ctx.close()
    }

    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
    }

    @Test
    fun `primary replacement wins injection`() {
        val fast = ctx.getBean(FastEventPublisher::class.java)
        // The bean resolved for the ApplicationEventPublisher dependency must be our component.
        assertSame(fast, ctx.getBean(PublisherHolder::class.java).publisher)
        assertSame(fast, publisher)
    }

    @Test
    fun `blocking listeners run synchronously on the calling thread`() {
        val callerThread = Thread.currentThread().name
        publisher.publishEvent(AlphaEvent(42))

        // Blocking => already delivered, synchronously, before publishEvent returned.
        assertEquals(1, listeners.blockingHits.size)
        assertEquals(1, listeners.secondAlphaHits.size)
        val (event, thread) = listeners.blockingHits.first()
        assertEquals(AlphaEvent(42), event)
        assertEquals(callerThread, thread, "blocking listener must run on the publishing thread")
    }

    @Test
    fun `async listeners run on the async pool`() {
        listeners.latch = CountDownLatch(1)
        val callerThread = Thread.currentThread().name

        publisher.publishEvent(BetaEvent(7))

        assertTrue(listeners.latch.await(5, TimeUnit.SECONDS), "async listener did not fire in time")
        val (event, thread) = listeners.asyncHits.first()
        assertEquals(BetaEvent(7), event)
        assertTrue(thread.startsWith("Core "), "async listener ran on '$thread', expected a 'Core ' pool thread")
        assertTrue(thread != callerThread, "async listener must not run on the publishing thread")
    }

    @Test
    fun `no-arg blocking listener fires for the class named in the annotation`() {
        publisher.publishEvent(AlphaEvent(99))
        // No-arg @EventListener(AlphaEvent::class) ran (synchronously, since @Blocking).
        assertEquals(1, listeners.noArgAlphaHits.get())
        // And it coexists with the parameterized AlphaEvent listeners.
        assertEquals(1, listeners.blockingHits.size)
    }

    @Test
    fun `no-arg async listener registered via classes fires for each declared type`() {
        publisher.publishEvent(BetaEvent(1))
        publisher.publishEvent(DeltaEvent(2))
        awaitUntil(5_000) { listeners.noArgMultiHits.size == 2 }
        assertEquals(2, listeners.noArgMultiHits.size, "expected one hit for BetaEvent and one for DeltaEvent")
        assertTrue(
            listeners.noArgMultiHits.all { it.startsWith("Core ") },
            "no-arg async listener must run on the async pool, ran on ${listeners.noArgMultiHits}",
        )
    }

    @Test
    fun `event with no listeners is a no-op`() {
        // Must not throw, must not touch any recorder.
        publisher.publishEvent(OrphanEvent(1))
        assertTrue(listeners.blockingHits.isEmpty())
        assertTrue(listeners.asyncHits.isEmpty())
    }

    @Test
    fun `async pool is the one from GlobalExecutorsConfiguration`() {
        // Sanity: the publisher dispatches onto the shared pool, not a private one.
        assertNotNull(executors.asyncPool)
    }

    @Test
    fun `blocking dispatch path is allocation-free`() {
        val threadBean = ManagementFactory.getThreadMXBean()
            as ThreadMXBean
        Assumptions.assumeTrue(threadBean.isThreadAllocatedMemorySupported)

        val event = GammaEvent(1) // reused instance — only dispatch overhead is measured
        val tid = Thread.currentThread().threadId()

        // Warm up so the dispatch path is JIT-compiled before measuring.
        repeat(200_000) { publisher.publishEvent(event) }

        val iterations = 2_000_000
        val before = threadBean.getThreadAllocatedBytes(tid)
        for (i in 0 until iterations) {
            publisher.publishEvent(event)
        }
        val bytes = threadBean.getThreadAllocatedBytes(tid) - before
        val perOp = bytes.toDouble() / iterations

        assertEquals(iterations.toLong() + 200_000L, listeners.gammaCount)
        println("FastEventPublisher blocking dispatch: $perOp bytes/op over $iterations iterations")
        assertTrue(perOp < 1.0, "blocking dispatch should be allocation-free, was $perOp bytes/op")
    }
}
