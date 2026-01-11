package fr.rowlaxx.springkutils.math.utils

import fr.rowlaxx.springkutils.math.data.MutableSparseVector
import fr.rowlaxx.springkutils.math.data.SparseVector
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min

object SparseVectorUtils {

    /**
     * Returns a new vector containing the element-wise minimum of [a] and [b].
     */
    fun min(a: SparseVector, b: SparseVector): SparseVector {
        val result = TreeMap<Int, Double>()
        val allIndices = a.content.keys + b.content.keys
        for (index in allIndices) {
            val minValue = min(a[index], b[index])
            if (minValue != 0.0) result[index] = minValue
        }
        return SparseVector(result)
    }

    /**
     * Returns a new vector containing the element-wise maximum of [a] and [b].
     */
    fun max(a: SparseVector, b: SparseVector): SparseVector {
        val result = TreeMap<Int, Double>()
        val allIndices = a.content.keys + b.content.keys
        for (index in allIndices) {
            val maxValue = max(a[index], b[index])
            if (maxValue != 0.0) result[index] = maxValue
        }
        return SparseVector(result)
    }

    /**
     * Serializes the [SparseVector] into a [ByteArray].
     *
     * The format is:
     * - Number of non-zero elements (Int, 4 bytes)
     * - For each element:
     *   - Index (Int, 4 bytes)
     *   - Value (Double, 8 bytes)
     *
     * @param instance The [SparseVector] to serialize.
     * @return The serialized [ByteArray].
     */
    fun serialize(instance: SparseVector): ByteArray {
        val content = instance.content
        val buffer = ByteBuffer.allocate(4 + content.size * 12)
        buffer.putInt(content.size)
        for ((index, value) in content) {
            buffer.putInt(index)
            buffer.putDouble(value)
        }
        return buffer.array()
    }

    /**
     * Deserializes a [SparseVector] from a [ByteArray].
     *
     * @param bytes The [ByteArray] to deserialize from.
     * @return A [MutableSparseVector] containing the deserialized data.
     * @throws IllegalArgumentException if the byte array is invalid or corrupted.
     */
    fun deserialize(bytes: ByteArray): MutableSparseVector {
        val buffer = ByteBuffer.wrap(bytes)
        val size = try {
            buffer.getInt()
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid byte array: cannot read size", e)
        }

        val content = TreeMap<Int, Double>()
        try {
            repeat(size) {
                val index = buffer.getInt()
                val value = buffer.getDouble()
                content[index] = value
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid byte array: cannot read elements", e)
        }

        return MutableSparseVector(content)
    }

}