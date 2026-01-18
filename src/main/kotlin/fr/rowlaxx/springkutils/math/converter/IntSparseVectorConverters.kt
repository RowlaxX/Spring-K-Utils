package fr.rowlaxx.springkutils.math.converter

import fr.rowlaxx.springkutils.math.data.MutableIntSparseVector
import fr.rowlaxx.springkutils.math.data.IntSparseVector
import fr.rowlaxx.springkutils.math.utils.IntSparseVectorUtils
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = true)
class IntSparseVectorConverter : AttributeConverter<IntSparseVector?, ByteArray?> {
    override fun convertToDatabaseColumn(attribute: IntSparseVector?): ByteArray? {
        return attribute?.let { IntSparseVectorUtils.serialize(it) }
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): IntSparseVector? {
        return dbData?.let { IntSparseVectorUtils.deserialize(it) }
    }
}

@Converter(autoApply = true)
class MutableIntSparseVectorConverter : AttributeConverter<MutableIntSparseVector?, ByteArray?> {
    override fun convertToDatabaseColumn(attribute: MutableIntSparseVector?): ByteArray? {
        return attribute?.let { IntSparseVectorUtils.serialize(it) }
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): MutableIntSparseVector? {
        return dbData?.let { IntSparseVectorUtils.deserialize(it) }
    }
}
