package fr.rowlaxx.springkutils.io.utils

import java.nio.ByteBuffer

object ByteBufferExtension {

    fun ByteBuffer.getBackingArray(): ByteArray {
        if (hasArray() && !isReadOnly()) {
            val from = arrayOffset() + position()
            val to = arrayOffset() + limit()
            return array().copyOfRange(from, to)
        } else {
            val bytes = ByteArray(remaining())
            duplicate().get(bytes)
            return bytes
        }
    }

}