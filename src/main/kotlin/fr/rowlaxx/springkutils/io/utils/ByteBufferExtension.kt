package fr.rowlaxx.springkutils.io.utils

import java.nio.ByteBuffer

/**
 * Extension methods for [ByteBuffer].
 */
object ByteBufferExtension {

    /**
     * Returns a copy of the backing array of this buffer.
     * 
     * If the buffer has an accessible backing array and is not read-only, it returns a clone of that array.
     * Otherwise, it creates a new byte array and fills it with the remaining bytes of the buffer (from current position to limit).
     * 
     * Note: In the latter case, the buffer's position will be advanced to its limit.
     * 
     * @return A byte array containing the contents of this buffer.
     */
    fun ByteBuffer.getBackingArray(): ByteArray {
        if (hasArray() && !isReadOnly()) {
            return array().clone()
        } else {
            val bytes = ByteArray(remaining())
            get(bytes)
            return bytes
        }
    }

}