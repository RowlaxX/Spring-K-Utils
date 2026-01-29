package fr.rowlaxx.springkutils.orm.converter

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.nio.ByteBuffer
import java.util.TreeMap
import kotlin.collections.iterator

@Converter
class TreeMapLongLongConverter : AttributeConverter<TreeMap<Long, Long>, ByteArray> {
    override fun convertToDatabaseColumn(attribute: TreeMap<Long, Long>?): ByteArray? {
        if (attribute == null) return null

        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES + attribute.size * (Long.SIZE_BYTES * 2))
        buffer.putInt(attribute.size)
        for ((key, value) in attribute) {
            buffer.putLong(key)
            buffer.putLong(value)
        }
        return buffer.array()
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): TreeMap<Long, Long>? {
        if (dbData == null || dbData.isEmpty()) return null

        val buffer = ByteBuffer.wrap(dbData)
        val size = buffer.int
        val map = TreeMap<Long, Long>()
        for (i in 0 until size) {
            val key = buffer.long
            val value = buffer.long
            map[key] = value
        }
        return map
    }
}