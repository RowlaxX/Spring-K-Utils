package fr.rowlaxx.springkutils.collection.set

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = true)
class MutableSegmentedBitSetConverter : AttributeConverter<MutableSegmentedBitSet?, ByteArray?> {
    override fun convertToDatabaseColumn(attribute: MutableSegmentedBitSet?): ByteArray? {
        return attribute?.let { SegmentedBitSetUtils.serialize(it) }
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): MutableSegmentedBitSet? {
        return dbData?.let { SegmentedBitSetUtils.deserialize(it) }
    }
}
