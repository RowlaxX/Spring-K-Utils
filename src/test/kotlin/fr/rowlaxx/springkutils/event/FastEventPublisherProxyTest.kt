package fr.rowlaxx.springkutils.event

import com.sun.management.ThreadMXBean
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import fr.rowlaxx.springkutils.event.annotation.Blocking
import fr.rowlaxx.springkutils.event.component.FastEventPublisher
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.lang.management.ManagementFactory

/** Test-local marker whose presence on a method makes the declaring bean an AOP (CGLIB) proxy. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class GuardedProxyMarker

data class ProxyDispatchEvent(val value: Int)

@Aspect
@Component
open class GuardCountingAspect {
    var guardedCalls = 0L

    @Around("@annotation(fr.rowlaxx.springkutils.event.GuardedProxyMarker)")
    open fun around(pjp: ProceedingJoinPoint): Any? {
        guardedCalls++
        return pjp.proceed()
    }
}

/**
 * A bean forced to be a CGLIB proxy (it has a [GuardedProxyMarker] method advised by an aspect) that
 * also carries a high-frequency [Blocking] listener with an allocation-free body, so the benchmark
 * measures only dispatch overhead.
 */
@Component
open class ProxiedBlockingListener {
    @Volatile
    var hits = 0L

    @GuardedProxyMarker
    open fun guarded() {
        // exists only to trigger proxying of this bean
    }

    @Blocking
    @EventListener
    open fun onProxiedEvent(event: ProxyDispatchEvent) {
        hits++
    }
}

@Configuration
@EnableAspectJAutoProxy
open class ProxyTestConfig

/**
 * Validates the design decision in [FastEventPublisher] to bind listeners to the **un-proxied
 * target** ([org.springframework.aop.framework.AopProxyUtils.getSingletonTarget]): even when the
 * listener bean is a real CGLIB AOP proxy, dispatch must stay allocation-free (it would not be if we
 * dispatched through the proxy's advice chain).
 */
class FastEventPublisherProxyTest {

    private lateinit var ctx: AnnotationConfigApplicationContext
    private lateinit var publisher: ApplicationEventPublisher
    private lateinit var listener: ProxiedBlockingListener

    @BeforeEach
    fun setUp() {
        ctx = AnnotationConfigApplicationContext().apply {
            register(
                ProxyTestConfig::class.java,
                GlobalThreadConfiguration::class.java,
                FastEventPublisher::class.java,
                GuardCountingAspect::class.java,
                ProxiedBlockingListener::class.java,
            )
            refresh()
        }
        publisher = ctx.getBean(ApplicationEventPublisher::class.java)
        listener = ctx.getBean(ProxiedBlockingListener::class.java)
    }

    @AfterEach
    fun tearDown() = ctx.close()

    @Test
    fun `listener bean really is an AOP proxy`() {
        // Guards the test itself: if the bean weren't proxied, the zero-alloc claim below is trivial.
        assertTrue(AopUtils.isAopProxy(listener), "expected ProxiedBlockingListener to be an AOP proxy")
        listener.guarded()
        assertEquals(1L, ctx.getBean(GuardCountingAspect::class.java).guardedCalls)
    }

    @Test
    fun `dispatch to a proxied bean's blocking listener is correct and allocation-free`() {
        val threadBean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(threadBean.isThreadAllocatedMemorySupported)

        val event = ProxyDispatchEvent(1)
        val tid = Thread.currentThread().threadId()
        val aspect = ctx.getBean(GuardCountingAspect::class.java)

        repeat(200_000) { publisher.publishEvent(event) }

        val iterations = 2_000_000
        val before = threadBean.getThreadAllocatedBytes(tid)
        for (i in 0 until iterations) {
            publisher.publishEvent(event)
        }
        val perOp = (threadBean.getThreadAllocatedBytes(tid) - before).toDouble() / iterations

        // Correctness: every publish reached the listener (through the un-proxied target).
        assertEquals(iterations.toLong() + 200_000L, listener.hits)
        // The listener method is not advised, so the advice chain must not have run.
        assertEquals(0L, aspect.guardedCalls, "listener dispatch must not touch the advice chain")
        println("FastEventPublisher proxied-bean blocking dispatch: $perOp bytes/op over $iterations iterations")
        // Had we bound to the proxy, CGLIB interception would allocate per call.
        assertTrue(perOp < 1.0, "dispatch to proxied bean should be allocation-free, was $perOp bytes/op")
    }
}
