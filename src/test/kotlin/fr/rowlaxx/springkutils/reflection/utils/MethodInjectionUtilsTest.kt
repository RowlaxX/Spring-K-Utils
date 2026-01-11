package fr.rowlaxx.springkutils.reflection.utils

import fr.rowlaxx.springkutils.reflection.utils.MethodInjectionUtils.invoke
import fr.rowlaxx.springkutils.reflection.utils.MethodInjectionUtils.invokeSuspend
import fr.rowlaxx.springkutils.reflection.utils.MethodInjectionUtils.toInjectionSupport
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Parameter
import kotlin.system.measureNanoTime
import kotlin.system.measureTimeMillis

class MethodInjectionUtilsTest {

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
