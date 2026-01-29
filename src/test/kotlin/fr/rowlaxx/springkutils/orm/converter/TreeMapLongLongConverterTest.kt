package fr.rowlaxx.springkutils.orm.converter

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

class TreeMapLongLongConverterTest {
    private val converter = TreeMapLongLongConverter()

    @Test
    fun `should convert null to null`() {
        assertNull(converter.convertToDatabaseColumn(null))
        assertNull(converter.convertToEntityAttribute(null))
    }

    @Test
    fun `should convert empty map`() {
        val map = TreeMap<Long, Long>()
        val bytes = converter.convertToDatabaseColumn(map)
        assertNotNull(bytes)
        
        val result = converter.convertToEntityAttribute(bytes)
        Assertions.assertEquals(0, result?.size)
    }

    @Test
    fun `should convert map with values`() {
        val map = TreeMap<Long, Long>()
        map[1L] = 10L
        map[2L] = 20L
        map[5L] = 50L

        val bytes = converter.convertToDatabaseColumn(map)
        assertNotNull(bytes)

        val result = converter.convertToEntityAttribute(bytes)
        assertNotNull(result)
        result!!
        assertEquals(3, result.size)
        Assertions.assertEquals(10L, result[1L])
        Assertions.assertEquals(20L, result[2L])
        Assertions.assertEquals(50L, result[5L])
        
        // Check order preservation
        assertEquals(listOf(1L, 2L, 5L), result.keys.toList())
    }
}
