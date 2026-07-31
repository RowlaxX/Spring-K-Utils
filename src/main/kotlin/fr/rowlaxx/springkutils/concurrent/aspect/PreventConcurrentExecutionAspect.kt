package fr.rowlaxx.springkutils.concurrent.aspect

import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Aspect that handles the [fr.rowlaxx.springkutils.concurrent.annotation.PreventConcurrentExecution] annotation.
 * It ensures that a method annotated with [fr.rowlaxx.springkutils.concurrent.annotation.PreventConcurrentExecution]
 * is not executed if it is already running.
 */
@Aspect
@Component
class PreventConcurrentExecutionAspect {
    private val executingMethods = ConcurrentHashMap.newKeySet<String>()

    @Around("@annotation(fr.rowlaxx.springkutils.concurrent.annotation.PreventConcurrentExecution)")
    fun preventConcurrentExecution(joinPoint: ProceedingJoinPoint): Any? {
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

        return skippedReturnValue(joinPoint)
    }

    /**
     * Returning null from advice on a method with a primitive return type makes Spring AOP throw
     * [org.springframework.aop.AopInvocationException] at the call site, so a skipped call must
     * yield the primitive's zero value instead.
     */
    private fun skippedReturnValue(joinPoint: ProceedingJoinPoint): Any? {
        val returnType = (joinPoint.signature as? MethodSignature)?.returnType ?: return null
        if (!returnType.isPrimitive || returnType == Void.TYPE) return null
        return when (returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Character.TYPE -> '\u0000'
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            else -> null
        }
    }
}
