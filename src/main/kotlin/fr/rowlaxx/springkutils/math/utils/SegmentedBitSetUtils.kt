package fr.rowlaxx.springkutils.math.utils

import fr.rowlaxx.springkutils.math.data.MutableSegmentedBitSet
import fr.rowlaxx.springkutils.math.data.SegmentedBitSet
import java.nio.ByteBuffer
import java.util.*

object SegmentedBitSetUtils {

    /**
     * Serializes the [SegmentedBitSet] into a [ByteArray].
     *
     * The format is:
     * - Number of segments (Int, 4 bytes)
     * - For each segment:
     *   - Start of range (Long, 8 bytes)
     *   - End of range (Long, 8 bytes)
     *
     * @param instance The [SegmentedBitSet] to serialize.
     * @return The serialized [ByteArray].
     */
    fun serialize(instance: SegmentedBitSet): ByteArray {
        val content = instance.content
        val buffer = ByteBuffer.allocate(4 + content.size * 16)
        buffer.putInt(content.size)
        for ((start, end) in content) {
            buffer.putLong(start)
            buffer.putLong(end)
        }
        return buffer.array()
    }

    /**
     * Deserializes a [SegmentedBitSet] from a [ByteArray].
     *
     * @param bytes The [ByteArray] to deserialize from.
     * @return A [MutableSegmentedBitSet] containing the deserialized data.
     * @throws IllegalArgumentException if the byte array is invalid or corrupted.
     */
    fun deserialize(bytes: ByteArray): MutableSegmentedBitSet {
        val buffer = ByteBuffer.wrap(bytes)
        val size = try {
            buffer.getInt()
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid byte array: cannot read size", e)
        }

        val content = TreeMap<Long, Long>()
        try {
            repeat(size) {
                val start = buffer.getLong()
                val end = buffer.getLong()
                content[start] = end
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid byte array: cannot read segments", e)
        }

        return MutableSegmentedBitSet(content)
    }

}