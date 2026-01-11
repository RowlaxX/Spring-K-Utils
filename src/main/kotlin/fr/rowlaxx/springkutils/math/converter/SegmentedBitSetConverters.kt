package fr.rowlaxx.springkutils.math.converter

import fr.rowlaxx.springkutils.math.data.MutableSegmentedBitSet
import fr.rowlaxx.springkutils.math.data.SegmentedBitSet
import fr.rowlaxx.springkutils.math.utils.SegmentedBitSetUtils
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

@Converter(autoApply = true)
class MutableSegmentedBitSetConverter : AttributeConverter<MutableSegmentedBitSet?, ByteArray?> {
    override fun convertToDatabaseColumn(attribute: MutableSegmentedBitSet?): ByteArray? {
        return attribute?.let { SegmentedBitSetUtils.serialize(it) }
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): MutableSegmentedBitSet? {
        return dbData?.let { SegmentedBitSetUtils.deserialize(it) }
    }
}
