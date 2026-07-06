package fr.rowlaxx.springkutils.math.utils

import fr.rowlaxx.springkutils.array.MutableLongLongEntangledArray
import fr.rowlaxx.springkutils.math.data.MutableSegmentedBitSet
import fr.rowlaxx.springkutils.math.data.SegmentedBitSet

object SegmentedBitSetUtils {

    /**
     * Serializes the [SegmentedBitSet] into a [ByteArray].
     *
     * Delegates to [MutableLongLongEntangledArray.serialize] on the backing store, whose format is:
     * - Number of segments (Int, 4 bytes)
     * - For each segment:
     *   - Start of range (Long, 8 bytes)
     *   - End of range (Long, 8 bytes)
     *
     * @param instance The [SegmentedBitSet] to serialize.
     * @return The serialized [ByteArray].
     */
    fun serialize(instance: SegmentedBitSet): ByteArray {
        return instance.content.serialize()
    }

    /**
     * Deserializes a [SegmentedBitSet] from a [ByteArray], via
     * [MutableLongLongEntangledArray.deserialize].
     *
     * @param bytes The [ByteArray] to deserialize from.
     * @return A [MutableSegmentedBitSet] containing the deserialized data.
     * @throws IllegalArgumentException if the byte array is invalid or corrupted.
     */
    fun deserialize(bytes: ByteArray): MutableSegmentedBitSet {
        return MutableSegmentedBitSet(MutableLongLongEntangledArray.deserialize(bytes))
    }

}
