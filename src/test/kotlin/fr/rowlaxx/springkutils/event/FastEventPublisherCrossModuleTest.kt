package fr.rowlaxx.springkutils.event

import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import fr.rowlaxx.springkutils.event.annotation.Blocking
import fr.rowlaxx.springkutils.event.component.FastEventPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.EventListener
import java.lang.invoke.MethodHandles
import java.util.concurrent.atomic.AtomicInteger

/** Shared (parent-loaded) so both classloaders, and the test, see the same instance. */
object CrossModuleSink {
    val hits = AtomicInteger()
}

/** Shared (parent-loaded) event so the dispatch key matches the published instance. */
data class CrossModuleEvent(val value: Int)

/**
 * This listener is deliberately reloaded by [IsolatingClassLoader] so it lives in a *different*
 * module than [FastEventPublisher] — reproducing spring-boot-devtools' split classloaders, where
 * `privateLookupIn` returns a teleported (non-full-privilege) lookup and LambdaMetafactory cannot be
 * used. The publisher must fall back to a bound MethodHandle.
 */
class CrossModuleListener {
    @Blocking
    @EventListener
    fun onEvent(event: CrossModuleEvent) {
        CrossModuleSink.hits.incrementAndGet()
    }
}

/** Defines [isolated] classes itself (own module); delegates everything else to the parent. */
private class IsolatingClassLoader(
    parent: ClassLoader,
    private val isolated: Set<String>,
) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name !in isolated) return super.loadClass(name, resolve)
        synchronized(getClassLoadingLock(name)) {
            findLoadedClass(name)?.let { return it }
            val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!.readBytes()
            return defineClass(name, bytes, 0, bytes.size).also { if (resolve) resolveClass(it) }
        }
    }
}

/**
 * Regression test for the "Invalid caller: ..." `LambdaConversionException` that occurred when a
 * listener bean was loaded by a different classloader/module than the publisher (the real-world
 * trigger was spring-boot-devtools). The cross-module fallback must keep dispatch working.
 */
class FastEventPublisherCrossModuleTest {

    @Test
    fun `dispatches to a listener loaded in a different module via the bound-handle fallback`() {
        CrossModuleSink.hits.set(0)
        val listenerName = CrossModuleListener::class.java.name
        val childLoader = IsolatingClassLoader(javaClass.classLoader, setOf(listenerName))
        val isolatedListener = childLoader.loadClass(listenerName)

        // Sanity: the listener really is in a different module, so its lookup is not full-privilege
        // (this is exactly what makes LambdaMetafactory unusable and forces the fallback).
        assertFalse(
            MethodHandles.privateLookupIn(isolatedListener, MethodHandles.lookup()).hasFullPrivilegeAccess(),
            "test precondition: isolated listener should yield a teleported lookup",
        )

        AnnotationConfigApplicationContext().use { ctx ->
            ctx.register(GlobalThreadConfiguration::class.java, FastEventPublisher::class.java)
            ctx.registerBeanDefinition("crossModuleListener", RootBeanDefinition(isolatedListener))
            ctx.refresh() // before the fix this threw LambdaConversionException("Invalid caller: ...")

            val publisher = ctx.getBean(ApplicationEventPublisher::class.java)
            publisher.publishEvent(CrossModuleEvent(1)) // @Blocking -> delivered synchronously

            assertEquals(1, CrossModuleSink.hits.get())
        }
    }
}
