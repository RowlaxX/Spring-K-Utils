package fr.rowlaxx.springkutils.event.component

import fr.rowlaxx.springkutils.concurrent.config.GlobalExecutorsConfiguration
import fr.rowlaxx.springkutils.event.annotation.Blocking
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.stereotype.Component
import org.springframework.util.ClassUtils
import org.springframework.util.ReflectionUtils
import java.lang.invoke.LambdaMetafactory
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Method
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask
import java.util.function.Consumer

/**
 * A drop-in, allocation-lean replacement for Spring's [ApplicationEventPublisher].
 *
 * Marked [@Primary][Primary] so every injected `ApplicationEventPublisher` resolves to this bean and
 * all `publishEvent(...)` calls route here. Spring's own multicaster is left untouched, so framework
 * lifecycle events (ContextRefreshed, ApplicationReady, ...) keep working as before.
 *
 * Listeners stay declared with the classic [EventListener] annotation. At startup this publisher
 * scans every bean for `@EventListener` methods and, for each, builds a zero-reflection invoker via
 * [LambdaMetafactory] (a JIT-inlinable [Consumer], bound to the un-proxied target instance). Both
 * forms are supported: a single-parameter method (event type = the parameter) and a no-argument
 * method that names its event class(es) in the annotation, e.g. `@EventListener(MyEvent::class)`.
 *
 * Dispatch is keyed on the **exact** runtime class of the event (`event.javaClass`) — no generic
 * type resolution, no listener-retriever cache, no `PayloadApplicationEvent` wrapper. Per publish:
 *  - a [fr.rowlaxx.springkutils.event.annotation.Blocking] listener runs inline on the publishing thread — **zero allocation**;
 *  - any other listener is submitted to the async pool as a single lightweight [ForkJoinTask] —
 *    **one allocation** (avoids the extra `RunnableExecuteAction` wrapper that `execute(Runnable)`
 *    would add).
 *
 * Ordering between listeners of the same event is unspecified.
 */
@Component
@Primary
class FastEventPublisher(
    private val applicationContext: ApplicationContext,
    executors: GlobalExecutorsConfiguration,
) : ApplicationEventPublisher, SmartInitializingSingleton {

    private val asyncPool: ForkJoinPool = executors.asyncPool

    private class Subscribers(
        @JvmField val blocking: Array<Consumer<Any>>,
        @JvmField val async: Array<Consumer<Any>>,
    )

    @Volatile
    private var registry: Map<Class<*>, Subscribers> = emptyMap()

    override fun publishEvent(event: Any) = dispatch(event)

    override fun publishEvent(event: ApplicationEvent) = dispatch(event)

    private fun dispatch(event: Any) {
        val subs = registry[event.javaClass] ?: return
        val blocking = subs.blocking
        for (i in blocking.indices) {
            blocking[i].accept(event)
        }
        val async = subs.async
        for (i in async.indices) {
            asyncPool.execute(AsyncEventTask(async[i], event))
        }
    }

    private class AsyncEventTask(
        private val consumer: Consumer<Any>,
        private val event: Any,
    ) : ForkJoinTask<Void?>() {
        override fun getRawResult(): Void? = null
        override fun setRawResult(value: Void?) {}

        override fun exec(): Boolean {
            try {
                consumer.accept(event)
            } catch (t: Throwable) {
                log.error("Async event listener failed for {}", event.javaClass.name, t)
            }
            return true
        }
    }

    override fun afterSingletonsInstantiated() {
        val blockingByType = HashMap<Class<*>, MutableList<Consumer<Any>>>()
        val asyncByType = HashMap<Class<*>, MutableList<Consumer<Any>>>()
        val lookup = MethodHandles.lookup()

        for (beanName in applicationContext.beanDefinitionNames) {
            val type = runCatching { applicationContext.getType(beanName) }.getOrNull() ?: continue
            val userClass = ClassUtils.getUserClass(type)

            val listenerMethods = ArrayList<Method>()
            ReflectionUtils.doWithMethods(userClass) { method ->
                if (!method.isBridge && !method.isSynthetic &&
                    AnnotatedElementUtils.hasAnnotation(method, EventListener::class.java)
                ) {
                    listenerMethods.add(method)
                }
            }
            if (listenerMethods.isEmpty()) continue

            val bean = applicationContext.getBean(beanName)
            val target = AopProxyUtils.getSingletonTarget(bean) ?: bean

            for (method in listenerMethods) {
                val bucket = if (AnnotatedElementUtils.hasAnnotation(method, Blocking::class.java)) {
                    blockingByType
                } else {
                    asyncByType
                }
                when (method.parameterCount) {
                    1 -> {
                        val eventType = method.parameterTypes[0]
                        val consumer = createConsumer(target, method, lookup)
                        bucket.getOrPut(eventType) { ArrayList() }.add(consumer)
                    }
                    0 -> {
                        val classes = AnnotatedElementUtils
                            .findMergedAnnotation(method, EventListener::class.java)
                            ?.classes
                        if (classes.isNullOrEmpty()) {
                            log.warn(
                                "Ignoring @EventListener {}.{}: a no-argument listener must declare its " +
                                    "event class(es) in the annotation, e.g. @EventListener(MyEvent::class)",
                                userClass.name, method.name,
                            )
                            continue
                        }
                        val consumer = createNoArgConsumer(target, method, lookup)
                        for (eventType in classes) {
                            bucket.getOrPut(eventType.java) { ArrayList() }.add(consumer)
                        }
                    }
                    else -> log.warn(
                        "Ignoring unsupported @EventListener {}.{}: expected zero or one parameter",
                        userClass.name, method.name,
                    )
                }
            }
        }

        val built = HashMap<Class<*>, Subscribers>()
        for (eventType in blockingByType.keys + asyncByType.keys) {
            built[eventType] = Subscribers(
                blocking = blockingByType[eventType]?.toTypedArray() ?: EMPTY,
                async = asyncByType[eventType]?.toTypedArray() ?: EMPTY,
            )
        }
        registry = built

        val total = blockingByType.values.sumOf { it.size } + asyncByType.values.sumOf { it.size }
        log.info("FastEventPublisher registered {} listeners across {} event types", total, built.size)
    }

    private fun createConsumer(target: Any, method: Method, lookup: MethodHandles.Lookup): Consumer<Any> {
        val declaringClass = method.declaringClass
        val privateLookup = MethodHandles.privateLookupIn(declaringClass, lookup)
        val handle = privateLookup.unreflect(method)
        // Fast path: a LambdaMetafactory-generated, JIT-inlinable Consumer. It needs a full-privilege
        // caller, which `privateLookupIn` cannot grant when the listener lives in a different
        // module/classloader than this publisher (e.g. under spring-boot-devtools' restart loader):
        // the lookup is "teleported" and LambdaMetafactory rejects it ("Invalid caller: ..."). The
        // bound-MethodHandle fallback is equally allocation-free and works across modules.
        if (privateLookup.hasFullPrivilegeAccess()) {
            val eventType = method.parameterTypes[0]
            val factory = LambdaMetafactory.metafactory(
                privateLookup,
                "accept",
                MethodType.methodType(Consumer::class.java, declaringClass),
                MethodType.methodType(Void.TYPE, Any::class.java),
                handle,
                MethodType.methodType(Void.TYPE, eventType),
            )
            @Suppress("UNCHECKED_CAST")
            return factory.target.invokeWithArguments(target) as Consumer<Any>
        }
        val bound = handle.bindTo(target).asType(MethodType.methodType(Void.TYPE, Any::class.java))
        return Consumer { bound.invoke(it) }
    }

    private fun createNoArgConsumer(target: Any, method: Method, lookup: MethodHandles.Lookup): Consumer<Any> {
        val declaringClass = method.declaringClass
        val privateLookup = MethodHandles.privateLookupIn(declaringClass, lookup)
        val handle = privateLookup.unreflect(method)
        if (privateLookup.hasFullPrivilegeAccess()) {
            val factory = LambdaMetafactory.metafactory(
                privateLookup,
                "run",
                MethodType.methodType(Runnable::class.java, declaringClass),
                MethodType.methodType(Void.TYPE),
                handle,
                MethodType.methodType(Void.TYPE),
            )
            val runnable = factory.target.invokeWithArguments(target) as Runnable
            return Consumer { runnable.run() }
        }
        val bound = handle.bindTo(target)
        return Consumer { bound.invoke() }
    }

    private companion object {
        private val EMPTY = emptyArray<Consumer<Any>>()
    }
}
