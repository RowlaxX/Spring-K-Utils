package fr.rowlaxx.springkutils.concurrent.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Aspect that handles the [fr.rowlaxx.springkutils.concurrent.annotation.PreventMultipleExecution] annotation.
 * It ensures that a method annotated with [fr.rowlaxx.springkutils.concurrent.annotation.PreventMultipleExecution]
 * is not executed if it is already running.
 */
@Aspect
@Component
class PreventMultipleExecutionAspect {
    private val executingMethods = ConcurrentHashMap.newKeySet<String>()

    @Around("@annotation(fr.rowlaxx.springkutils.concurrent.annotation.PreventMultipleExecution)")
    fun preventMultipleExecution(joinPoint: ProceedingJoinPoint): Any? {
        val methodSignature = joinPoint.signature.toLongString()
        val target = joinPoint.target
        val methodKey = "${target.javaClass.name}#$methodSignature"

        if (executingMethods.add(methodKey)) {
            try {
                return joinPoint.proceed()
            } finally {
                executingMethods.remove(methodKey)
            }
        }

        return null
    }
}
