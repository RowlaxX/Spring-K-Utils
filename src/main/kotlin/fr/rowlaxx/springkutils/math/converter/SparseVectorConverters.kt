package fr.rowlaxx.springkutils.math.converter

import fr.rowlaxx.springkutils.math.data.MutableSparseVector
import fr.rowlaxx.springkutils.math.data.SparseVector
import fr.rowlaxx.springkutils.math.utils.SparseVectorUtils
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = true)
class SparseVectorConverter : AttributeConverter<SparseVector?, ByteArray?> {
    override fun convertToDatabaseColumn(attribute: SparseVector?): ByteArray? {
        return attribute?.let { SparseVectorUtils.serialize(it) }
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): SparseVector? {
        return dbData?.let { SparseVectorUtils.deserialize(it) }
    }
}

@Converter(autoApply = true)
class MutableSparseVectorConverter : AttributeConverter<MutableSparseVector?, ByteArray?> {
    override fun convertToDatabaseColumn(attribute: MutableSparseVector?): ByteArray? {
        return attribute?.let { SparseVectorUtils.serialize(it) }
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): MutableSparseVector? {
        return dbData?.let { SparseVectorUtils.deserialize(it) }
    }
}
