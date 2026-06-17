package fr.rowlaxx.springkutils.event.annotation

/**
 * Marks an [org.springframework.context.event.EventListener] method, dispatched by
 * [fr.rowlaxx.springkutils.event.component.FastEventPublisher], to run **synchronously on the publishing thread** instead of being
 * dispatched to the async pool.
 *
 * By default every listener is fired on the async pool (see
 * [fr.rowlaxx.springkutils.concurrent.config.GlobalExecutorsConfiguration.asyncPool]). Annotate a
 * listener with [Blocking] when it must run inline — typically very high frequency, cheap listeners
 * (e.g. accumulator ingests) for which scheduling an async task per event would cost more than the
 * work itself.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Blocking