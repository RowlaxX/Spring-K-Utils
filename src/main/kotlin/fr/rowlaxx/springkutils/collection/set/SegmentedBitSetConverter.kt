package fr.rowlaxx.springkutils.collection.set

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = true)
class SegmentedBitSetConverter : AttributeConverter<SegmentedBitSet?, ByteArray?> {
    override fun convertToDatabaseColumn(attribute: SegmentedBitSet?): ByteArray? {
        return attribute?.let { SegmentedBitSetUtils.serialize(it) }
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): SegmentedBitSet? {
        return dbData?.let { SegmentedBitSetUtils.deserialize(it) }
    }
}