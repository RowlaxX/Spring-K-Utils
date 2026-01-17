package fr.rowlaxx.springkutils.concurrent.annotation

/**
 * Annotation used to prevent multiple concurrent executions of the same method.
 * If the method is already being executed, subsequent calls will be ignored.
 *
 * This annotation must be used on methods within Spring-managed beans and requires
 * [fr.rowlaxx.springkutils.concurrent.aspect.PreventMultipleExecutionAspect] to be active.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class PreventMultipleExecution
