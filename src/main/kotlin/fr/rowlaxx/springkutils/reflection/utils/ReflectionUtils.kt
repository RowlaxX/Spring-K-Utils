package fr.rowlaxx.springkutils.reflection.utils

import org.springframework.aop.support.AopUtils
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.jvm.java
import kotlin.reflect.KClass

/**
 * Utility class for reflection operations, compatible with Spring's AOP proxies.
 */
object ReflectionUtils {

    /**
     * Finds all methods on the given object that are annotated with the specified annotation.
     * Handles Spring AOP proxies by looking at the target class.
     *
     * @param obj The object to inspect.
     * @param annotation The annotation class to look for.
     * @return A list of pairs, each containing the annotation instance and the method it was found on.
     */
    fun <T : Annotation> findMethodsWithAnnotation(obj: Any, annotation: KClass<T>): List<Pair<T, Method>> {
        val result = mutableListOf<Pair<T, Method>>()
        val type = AopUtils.getTargetClass(obj)

        org.springframework.util.ReflectionUtils.doWithMethods(type) {
            it.getAnnotation(annotation.java)?.let { a ->
                result.add(a to it)
            }
        }

        return result
    }

    /**
     * Finds all fields on the given object that are annotated with the specified annotation.
     * Handles Spring AOP proxies by looking at the target class.
     *
     * @param obj The object to inspect.
     * @param annotation The annotation class to look for.
     * @return A list of pairs, each containing the annotation instance and the field it was found on.
     */
    fun <T : Annotation> findFieldsWithAnnotation(obj: Any, annotation: KClass<T>): List<Pair<T, Field>> {
        val result = mutableListOf<Pair<T, Field>>()
        val type = AopUtils.getTargetClass(obj)

        org.springframework.util.ReflectionUtils.doWithFields(type) {
            it.getAnnotation(annotation.java)?.let { a ->
                result.add(a to it)
            }
        }

        return result
    }

    /**
     * Finds all methods on the given object that have the specified return type.
     * Handles Spring AOP proxies by looking at the target class.
     *
     * @param obj The object to inspect.
     * @param type The return type to look for.
     * @return A list of methods that have the specified return type.
     */
    fun findMethodsWithReturnType(obj: Any, type: Class<*>): List<Method> {
        val result = mutableListOf<Method>()
        val instanceType = AopUtils.getTargetClass(obj)

        org.springframework.util.ReflectionUtils.doWithMethods(instanceType) {
            if (it.returnType == type) {
                result.add(it)
            }
        }

        return result
    }

    /**
     * Finds all fields on the given object that have the specified type.
     * Handles Spring AOP proxies by looking at the target class.
     *
     * @param obj The object to inspect.
     * @param type The type of the fields to look for.
     * @return A list of fields that have the specified type.
     */
    fun findFieldsWithType(obj: Any, type: Class<*>): List<Field> {
        val result = mutableListOf<Field>()
        val instanceType = AopUtils.getTargetClass(obj)

        org.springframework.util.ReflectionUtils.doWithFields(instanceType) {
            if (it.type == type) {
                result.add(it)
            }
        }

        return result
    }
}