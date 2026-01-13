package fr.rowlaxx.springkutils.reflection.utils

import kotlinx.coroutines.runBlocking
import java.lang.reflect.Method
import java.lang.reflect.Parameter
import kotlin.reflect.full.callSuspend
import kotlin.reflect.jvm.kotlinFunction

/**
 * Utility class for invoking methods with dependency injection.
 */
object InjectionUtils {

    /**
     * Data class that supports method injection.
     * Contains the method to invoke and its pre-resolved metadata.
     */
    data class Injection(
        val method: Method,
        val isSuspend: Boolean,
        val parameters: List<Pair<Parameter, Class<*>>>
    )

    private fun Class<*>.toWrapper(): Class<*> {
        return if (this.isPrimitive) {
            when (this) {
                Int::class.java -> Int::class.javaObjectType
                Long::class.java -> Long::class.javaObjectType
                Boolean::class.java -> Boolean::class.javaObjectType
                Double::class.java -> Double::class.javaObjectType
                Float::class.java -> Float::class.javaObjectType
                Byte::class.java -> Byte::class.javaObjectType
                Short::class.java -> Short::class.javaObjectType
                Char::class.java -> Char::class.javaObjectType
                else -> this
            }
        } else this
    }

    private fun Injection.resolveArguments(argumentResolver: (Parameter, Class<*>) -> Any?): Array<Any?> {
        return parameters.map { argumentResolver(it.first, it.second) }
                        .toTypedArray()
    }
    
    private fun asArgumentResolver(vararg arguments: Any?): (Parameter, Class<*>) -> Any? {
        return { _, clazz ->
            val targetClass = clazz.toWrapper()
            arguments.firstOrNull { it != null && targetClass.isInstance(it) }
        }
    }
    
    /**
     * Checks if the method can be invoked with the given [classes] as arguments.
     * This method verifies if each parameter of the [Injection] can be satisfied by at least one of the provided classes.
     * A parameter is satisfied if its type is assignable from the provided class.
     *
     * @param classes the classes of the available arguments.
     * @return true if all method parameters can be assigned from the given classes, false otherwise.
     */
    fun Injection.canInvoke(vararg classes: Class<*>): Boolean {
        val wrappers = classes.map { it.toWrapper() }
        return parameters.map { it.second.toWrapper() }
            .all { p -> wrappers.any { c -> p.isAssignableFrom(c) } }
    }
    
    /**
     * Checks if the method can be invoked with the given [args] as arguments.
     * This is a convenience method that calls [canInvoke] with the classes of the provided arguments.
     * Note: If an argument is null, it will be ignored as its class cannot be determined.
     *
     * @param args the available arguments.
     * @return true if all method parameters can be assigned from the classes of the given arguments, false otherwise.
     */
    fun Injection.canInvoke(vararg args: Any): Boolean {
        return canInvoke(*args.map { it.javaClass }.toTypedArray())
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
    fun Injection.invoke(instance: Any?, argumentResolver: (Parameter, Class<*>) -> Any?): Any? {
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
     * Invokes the method on the given [instance] using the provided [arguments].
     * If the method is a suspend function, it will be executed using [runBlocking].
     *
     * @param instance the object on which to invoke the method (null for static methods)
     * @param arguments the arguments to pass to the method
     * @return the result of the method invocation
     * @throws Exception if the method invocation fails
     */
    fun Injection.invoke(instance: Any?, vararg arguments: Any?): Any? {
        return invoke(instance, asArgumentResolver(*arguments))
    }

    /**
     * Invokes the method on the given [instance] using the provided [argumentResolver] in a suspending way.
     *
     * @param instance the object on which to invoke the method (null for static methods)
     * @param argumentResolver a function that resolves the value for each parameter
     * @return the result of the method invocation
     * @throws Exception if the method invocation fails
     */
    suspend fun Injection.invokeSuspend(instance: Any?, argumentResolver: (Parameter, Class<*>) -> Any?): Any? {
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
     * Invokes the method on the given [instance] using the provided [arguments] in a suspending way.
     *
     * @param instance the object on which to invoke the method (null for static methods)
     * @param arguments the arguments to pass to the method
     * @return the result of the method invocation
     * @throws Exception if the method invocation fails
     */
    suspend fun Injection.invokeSuspend(instance: Any?, vararg arguments: Any?): Any? {
        return invokeSuspend(instance, asArgumentResolver(*arguments))
    }

    /**
     * Converts a [Method] to a [Injection].
     * This pre-resolves whether the method is a suspend function and its parameters.
     *
     * @return a [Injection] for this method
     */
    fun Method.toInjectionSupport(): Injection {
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
        
        return Injection(
            isSuspend = isSuspend,
            parameters = parameters,
            method = this
        )
    }
    
}