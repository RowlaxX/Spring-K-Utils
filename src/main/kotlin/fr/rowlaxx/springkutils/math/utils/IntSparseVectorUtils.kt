package fr.rowlaxx.springkutils.math.utils

import fr.rowlaxx.springkutils.math.data.MutableIntSparseVector
import fr.rowlaxx.springkutils.math.data.IntSparseVector
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min

object IntSparseVectorUtils {

    /**
     * Returns a new vector containing the element-wise minimum of [a] and [b].
     */
    fun min(a: IntSparseVector, b: IntSparseVector): IntSparseVector {
        val result = TreeMap<Int, Int>()
        val allIndices = a.content.keys + b.content.keys
        for (index in allIndices) {
            val minValue = min(a[index], b[index])
            if (minValue != 0) result[index] = minValue
        }
        return IntSparseVector(result)
    }

    /**
     * Returns a new vector containing the element-wise maximum of [a] and [b].
     */
    fun max(a: IntSparseVector, b: IntSparseVector): IntSparseVector {
        val result = TreeMap<Int, Int>()
        val allIndices = a.content.keys + b.content.keys
        for (index in allIndices) {
            val maxValue = max(a[index], b[index])
            if (maxValue != 0) result[index] = maxValue
        }
        return IntSparseVector(result)
    }

    /**
     * Serializes the [IntSparseVector] into a [ByteArray].
     *
     * The format is:
     * - Number of non-zero elements (Int, 4 bytes)
     * - For each element:
     *   - Index (Int, 4 bytes)
     *   - Value (Int, 4 bytes)
     *
     * @param instance The [IntSparseVector] to serialize.
     * @return The serialized [ByteArray].
     */
    fun serialize(instance: IntSparseVector): ByteArray {
        val content = instance.content
        val buffer = ByteBuffer.allocate(4 + content.size * 8)
        buffer.putInt(content.size)
        for ((index, value) in content) {
            buffer.putInt(index)
            buffer.putInt(value)
        }
        return buffer.array()
    }

    /**
     * Deserializes a [IntSparseVector] from a [ByteArray].
     *
     * @param bytes The [ByteArray] to deserialize from.
     * @return A [MutableIntSparseVector] containing the deserialized data.
     * @throws IllegalArgumentException if the byte array is invalid or corrupted.
     */
    fun deserialize(bytes: ByteArray): MutableIntSparseVector {
        val buffer = ByteBuffer.wrap(bytes)
        val size = try {
            buffer.getInt()
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid byte array: cannot read size", e)
        }

        val content = TreeMap<Int, Int>()
        try {
            repeat(size) {
                val index = buffer.getInt()
                val value = buffer.getInt()
                content[index] = value
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid byte array: cannot read elements", e)
        }

        return MutableIntSparseVector(content)
    }

}
