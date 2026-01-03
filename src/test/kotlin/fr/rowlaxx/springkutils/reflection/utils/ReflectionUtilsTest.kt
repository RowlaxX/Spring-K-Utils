package fr.rowlaxx.springkutils.reflection.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReflectionUtilsTest {

    @Retention(AnnotationRetention.RUNTIME)
    annotation class TestAnnotation(val value: String)

    class TestClass {
        @field:TestAnnotation("field1")
        val field1: String = "v1"

        val field2: Int = 0

        @TestAnnotation("method1")
        fun method1(): String = "m1"

        fun method2(): Int = 2

        @TestAnnotation("method3")
        fun method3(): String = "m3"
    }

    @Test
    fun `findMethodsWithAnnotation should find all annotated methods`() {
        val obj = TestClass()
        val results = ReflectionUtils.findMethodsWithAnnotation(obj, TestAnnotation::class)

        val methodNames = results.map { it.second.name }
        
        assertTrue(methodNames.contains("method1"))
        assertTrue(methodNames.contains("method3"))
        // It might contain synthetic methods, so we check that the ones we want are there
        // and we check their annotation values
        val annotation1 = results.find { it.second.name == "method1" }?.first
        assertEquals("method1", annotation1?.value)
        val annotation3 = results.find { it.second.name == "method3" }?.first
        assertEquals("method3", annotation3?.value)
    }

    @Test
    fun `findFieldsWithAnnotation should find all annotated fields`() {
        val obj = TestClass()
        val results = ReflectionUtils.findFieldsWithAnnotation(obj, TestAnnotation::class)

        val fieldNames = results.map { it.second.name }
        assertTrue(fieldNames.contains("field1"))
        assertEquals("field1", results.find { it.second.name == "field1" }?.first?.value)
    }

    @Test
    fun `findMethodsWithReturnType should find all methods with specified return type`() {
        val obj = TestClass()
        
        val stringMethods = ReflectionUtils.findMethodsWithReturnType(obj, String::class.java)
        val stringMethodNames = stringMethods.map { it.name }
        assertTrue(stringMethodNames.contains("method1"))
        assertTrue(stringMethodNames.contains("method3"))
        // getField1 is also a method returning String in Kotlin/Java bytecode
        assertTrue(stringMethodNames.contains("getField1"))

        val intMethods = ReflectionUtils.findMethodsWithReturnType(obj, Int::class.java)
        val intMethodNames = intMethods.map { it.name }
        assertTrue(intMethodNames.contains("method2"))
        assertTrue(intMethodNames.contains("getField2"))
    }

    @Test
    fun `findFieldsWithType should find all fields with specified type`() {
        val obj = TestClass()

        val stringFields = ReflectionUtils.findFieldsWithType(obj, String::class.java)
        val stringFieldNames = stringFields.map { it.name }
        assertEquals(1, stringFields.size)
        assertTrue(stringFieldNames.contains("field1"))

        val intFields = ReflectionUtils.findFieldsWithType(obj, Int::class.java)
        val intFieldNames = intFields.map { it.name }
        assertEquals(1, intFields.size)
        assertTrue(intFieldNames.contains("field2"))
    }
}
