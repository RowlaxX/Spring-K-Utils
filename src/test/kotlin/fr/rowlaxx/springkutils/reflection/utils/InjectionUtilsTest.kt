package fr.rowlaxx.springkutils.reflection.utils

import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.canInvoke
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.invoke
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.invokeSuspend
import fr.rowlaxx.springkutils.reflection.utils.InjectionUtils.toInjectionSupport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Parameter
import kotlin.system.measureTimeMillis

class InjectionUtilsTest {

    class TestService {
        fun greet(name: String, age: Int): String {
            return "Hello $name, you are $age years old."
        }

        suspend fun greetSuspend(name: String, delayMillis: Long): String {
            delay(delayMillis)
            return "Hello $name after delay."
        }

        fun fail(message: String): Nothing {
            throw IllegalArgumentException(message)
        }
        
        companion object {
            @JvmStatic
            fun staticGreet(name: String): String {
                return "Hello $name from static."
            }
        }
    }

    private val service = TestService()

    @Test
    fun `should invoke normal method correctly`() {
        val method = TestService::class.java.getMethod("greet", String::class.java, Int::class.java)
        val support = method.toInjectionSupport()
        
        val result = support.invoke(service) { param, type ->
            when (type) {
                String::class.java -> "John"
                Int::class.java -> 30
                else -> null
            }
        }
        
        assertEquals("Hello John, you are 30 years old.", result)
    }

    @Test
    fun `canInvoke should return true when all types are present`() {
        val method = TestService::class.java.getMethod("greet", String::class.java, Int::class.java)
        val support = method.toInjectionSupport()
        
        val result = support.canInvoke(String::class.java, Int::class.java)
        assertEquals(true, result)
    }

    @Test
    fun `canInvoke should return false when some types are missing`() {
        val method = TestService::class.java.getMethod("greet", String::class.java, Int::class.java)
        val support = method.toInjectionSupport()
        
        val result = support.canInvoke(String::class.java)
        assertEquals(false, result)
    }

    @Test
    fun `canInvoke should return true for method with no parameters`() {
        class NoParam { fun test() {} }
        val method = NoParam::class.java.getMethod("test")
        val support = method.toInjectionSupport()
        
        val result = support.canInvoke(*emptyArray<Class<*>>())
        assertEquals(true, result)
    }

    @Test
    fun `canInvoke should handle subclasses correctly`() {
        open class Parent
        class Child : Parent()
        class TestClass { fun test(p: Parent) {} }
        
        val method = TestClass::class.java.getMethod("test", Parent::class.java)
        val support = method.toInjectionSupport()
        
        assertEquals(true, support.canInvoke(Child::class.java), "Should be able to invoke with Child class")
        assertEquals(true, support.canInvoke(Child()), "Should be able to invoke with Child instance")
    }

    @Test
    fun `canInvoke should handle primitives and wrappers`() {
        class TestClass { fun test(i: Int) {} }
        val method = TestClass::class.java.getMethod("test", Int::class.javaPrimitiveType)
        val support = method.toInjectionSupport()
        
        // Note: isAssignableFrom for primitives can be tricky. 
        // Int::class.java is Integer.class in Kotlin if not careful.
        // InjectionUtils uses it.second which is Parameter.type
        
        assertEquals(true, support.canInvoke(Int::class.javaObjectType), "Should be able to invoke with Integer.class")
        assertEquals(true, support.canInvoke(1), "Should be able to invoke with Int instance")
    }

    @Test
    fun `canInvoke with multiple candidates`() {
        class TestClass { fun test(s: String, i: Int) {} }
        val method = TestClass::class.java.getMethod("test", String::class.java, Int::class.javaPrimitiveType)
        val support = method.toInjectionSupport()
        
        assertEquals(true, support.canInvoke(String::class.java, Int::class.javaObjectType, Double::class.java))
        assertEquals(true, support.canInvoke(10.0, "test", 1))
        assertEquals(false, support.canInvoke("test", 10.0))
    }

    @Test
    fun `invoke with varargs should work for normal method`() {
        val method = TestService::class.java.getMethod("greet", String::class.java, Int::class.java)
        val support = method.toInjectionSupport()
        
        val result = support.invoke(service, "Jane", 25)
        assertEquals("Hello Jane, you are 25 years old.", result)
    }

    @Test
    fun `invoke with varargs should work for static method`() {
        val method = TestService::class.java.getMethod("staticGreet", String::class.java)
        val support = method.toInjectionSupport()
        
        val result = support.invoke(null, "Jane")
        assertEquals("Hello Jane from static.", result)
    }

    @Test
    fun `invoke with varargs should work for suspend method (blocking)`() {
        val method = TestService::class.java.methods.find { it.name == "greetSuspend" }!!
        val support = method.toInjectionSupport()
        
        val result = support.invoke(service, "Jane", 50L)
        assertEquals("Hello Jane after delay.", result)
    }

    @Test
    fun `invokeSuspend with varargs should work for normal method`() = runBlocking {
        val method = TestService::class.java.getMethod("greet", String::class.java, Int::class.java)
        val support = method.toInjectionSupport()
        
        val result = support.invokeSuspend(service, "Jane", 25)
        assertEquals("Hello Jane, you are 25 years old.", result)
    }

    @Test
    fun `invokeSuspend with varargs should work for suspend method`() = runBlocking {
        val method = TestService::class.java.methods.find { it.name == "greetSuspend" }!!
        val support = method.toInjectionSupport()
        
        val result = support.invokeSuspend(service, "Jane", 50L)
        assertEquals("Hello Jane after delay.", result)
    }

    @Test
    fun `invokeSuspend with varargs should work for static method`() = runBlocking {
        val method = TestService::class.java.getMethod("staticGreet", String::class.java)
        val support = method.toInjectionSupport()
        
        val result = support.invokeSuspend(null, "Jane")
        assertEquals("Hello Jane from static.", result)
    }

    @Test
    fun `should invoke static method correctly`() {
        val method = TestService::class.java.getMethod("staticGreet", String::class.java)
        val support = method.toInjectionSupport()
        
        val result = support.invoke(null) { _, _ -> "John" }
        
        assertEquals("Hello John from static.", result)
    }

    @Test
    fun `should invoke suspend method using invoke (blocking)`() {
        val method = TestService::class.java.methods.find { it.name == "greetSuspend" }!!
        val support = method.toInjectionSupport()
        
        val result = support.invoke(service) { _, type ->
            when (type) {
                String::class.java -> "John"
                Long::class.javaObjectType, Long::class.java -> 10L
                else -> null
            }
        }
        
        assertEquals("Hello John after delay.", result)
    }

    @Test
    fun `should invoke suspend method using invokeSuspend`() = runBlocking {
        val method = TestService::class.java.methods.find { it.name == "greetSuspend" }!!
        val support = method.toInjectionSupport()
        
        val result = support.invokeSuspend(service) { _, type ->
            when (type) {
                String::class.java -> "John"
                Long::class.javaObjectType, Long::class.java -> 10L
                else -> null
            }
        }
        
        assertEquals("Hello John after delay.", result)
    }

    @Test
    fun `should handle exceptions correctly`() {
        val method = TestService::class.java.getMethod("fail", String::class.java)
        val support = method.toInjectionSupport()
        
        assertThrows<IllegalArgumentException> {
            support.invoke(service) { _, _ -> "Error" }
        }
    }

    @Test
    fun `test speed of invocation`() {
        val method = TestService::class.java.getMethod("greet", String::class.java, Int::class.java)
        val support = method.toInjectionSupport()
        val resolver: (Parameter, Class<*>) -> Any? = { _, type ->
            if (type == String::class.java) "John" else 30
        }

        // Warm up
        repeat(1000) {
            support.invoke(service, resolver)
        }

        val iterations = 100_000
        val timeSupport = measureTimeMillis {
            repeat(iterations) {
                support.invoke(service, resolver)
            }
        }

        val timeDirect = measureTimeMillis {
            repeat(iterations) {
                service.greet("John", 30)
            }
        }
        
        val timeReflect = measureTimeMillis {
            repeat(iterations) {
                method.invoke(service, "John", 30)
            }
        }

        println("Support: $timeSupport ms, Direct: $timeDirect ms, Reflect: $timeReflect ms for $iterations iterations")
    }
}
