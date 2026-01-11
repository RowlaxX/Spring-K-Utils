package fr.rowlaxx.springkutils.reflection.utils

import kotlinx.coroutines.runBlocking
import org.springframework.core.MethodParameter
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.kotlinFunction

/**
 * Utility class for invoking methods with dependency injection.
 */
object MethodInjectionUtils {

    /**
     * Data class that supports method injection.
     * Contains the method to invoke and its pre-resolved metadata.
     */
    data class MethodInjectionSupport(
        val method: Method,
        val isSuspend: Boolean,
        val parameters: List<Pair<Parameter, Class<*>>>
    )

    private fun MethodInjectionSupport.resolveArguments(argumentResolver: (Parameter, Class<*>) -> Any?): Array<Any?> {
        return parameters.map { argumentResolver(it.first, it.second) }
                        .toTypedArray()
    }

    /**
     * Invokes the method on the given [instance] using the provided [argumentResolver].
     * If the method is a suspend function, it will be executed using [runBlocking].
     *
     * @param instance the object on which to invoke the method (null for static methods)
     * @param argumentResolver a function that resolves the value for each parameter
     * @return the result of the method invocation
     * @throws Exception if the method invocation fails
     */
    fun MethodInjectionSupport.invoke(instance: Any?, argumentResolver: (Parameter, Class<*>) -> Any?): Any? {
        val args = resolveArguments(argumentResolver)
        
        try {
            return if (isSuspend) {
                if (instance == null) {
                    runBlocking { method.kotlinFunction!!.callSuspend(*args) }    
                } else {
                    runBlocking { method.kotlinFunction!!.callSuspend(instance, *args) }
                }
            } else {
                method.invoke(instance, *args)
            }
        } catch (e: Exception) {
            throw e.cause ?: e
        }
    }

    /**
     * Invokes the method on the given [instance] using the provided [argumentResolver] in a suspending way.
     *
     * @param instance the object on which to invoke the method (null for static methods)
     * @param argumentResolver a function that resolves the value for each parameter
     * @return the result of the method invocation
     * @throws Exception if the method invocation fails
     */
    suspend fun MethodInjectionSupport.invokeSuspend(instance: Any?, argumentResolver: (Parameter, Class<*>) -> Any?): Any? {
        val args = resolveArguments(argumentResolver)
        
        try {
            return if (isSuspend) {
                if (instance == null) {
                    method.kotlinFunction!!.callSuspend(*args)
                } else {
                    method.kotlinFunction!!.callSuspend(instance, *args)
                }
            } else {
                method.invoke(instance, *args)
            }
        } catch (e: Exception) {
            throw e.cause ?: e
        }
    }

    /**
     * Converts a [Method] to a [MethodInjectionSupport].
     * This pre-resolves whether the method is a suspend function and its parameters.
     *
     * @return a [MethodInjectionSupport] for this method
     */
    fun Method.toInjectionSupport(): MethodInjectionSupport {
        val kFunc = kotlinFunction
        val isSuspend = kFunc?.isSuspend ?: false
        
        val parameters = if (isSuspend) {
            // Drop the last parameter (Continuation) for suspend functions
            this.parameters.dropLast(1)
        } else {
            this.parameters.toList()
        }.map {
            // Use MethodParameter for better type resolution if needed, 
            // but for now we stick to Parameter and its type.
            Pair(it, it.type)
        }
        
        return MethodInjectionSupport(
            isSuspend = isSuspend,
            parameters = parameters,
            method = this
        )
    }
    
}