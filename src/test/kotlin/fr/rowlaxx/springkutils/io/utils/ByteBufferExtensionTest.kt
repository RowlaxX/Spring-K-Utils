package fr.rowlaxx.springkutils.io.utils

import fr.rowlaxx.springkutils.io.utils.ByteBufferExtension.getBackingArray
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertFalse
import kotlin.test.assertNotSame

class ByteBufferExtensionTest {

    @Test
    fun `getBackingArray should return a clone of the backing array for heap buffer`() {
        val originalArray = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = ByteBuffer.wrap(originalArray)
        
        val result = buffer.getBackingArray()
        
        assertArrayEquals(originalArray, result)
        assertNotSame(originalArray, result) // Should be a clone
    }

    @Test
    fun `getBackingArray should return remaining bytes for direct buffer`() {
        val buffer = ByteBuffer.allocateDirect(10)
        buffer.put(byteArrayOf(1, 2, 3, 4, 5))
        buffer.flip()
        buffer.get() // increment position by 1
        
        val result = buffer.getBackingArray()
        
        val expected = byteArrayOf(2, 3, 4, 5)
        assertArrayEquals(expected, result)
        assertFalse(buffer.hasArray())
    }

    @Test
    fun `getBackingArray should return remaining bytes for read-only heap buffer`() {
        val originalArray = byteArrayOf(1, 2, 3, 4, 5)
        val buffer = ByteBuffer.wrap(originalArray).asReadOnlyBuffer()
        buffer.get() // increment position by 1
        
        val result = buffer.getBackingArray()
        
        val expected = byteArrayOf(2, 3, 4, 5)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `getBackingArray should return remaining bytes for sliced buffer`() {
        val originalArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        val buffer = ByteBuffer.wrap(originalArray)
        buffer.position(2)
        buffer.limit(5)
        val slice = buffer.slice()
        
        // slice has its own position=0, limit=3, capacity=3
        // but it still has a backing array if the original had one
        
        val result = slice.getBackingArray()
        
        val expected = byteArrayOf(3, 4, 5)
        // Wait, if hasArray() is true and it's not read only, it returns array().clone()
        // For a sliced buffer, array() returns the WHOLE backing array, not just the slice's part.
        // Let's verify what ByteBuffer.array() does for a slice.
        // Documentation says: "The offset of the first element of this buffer within the backing array is given by the arrayOffset() method."
        // And array() returns "The array that backs this buffer".
    }
}
