package fr.rowlaxx.springkutils.array

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = true)
class DoubleDoubleEntangledArrayConverter : AttributeConverter<DoubleDoubleEntangledArray?, ByteArray?> {

    override fun convertToDatabaseColumn(attribute: DoubleDoubleEntangledArray?): ByteArray? {
        return attribute?.serialize()
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): DoubleDoubleEntangledArray? {
        return dbData?.let { DoubleDoubleEntangledArray.deserialize(it) }
    }

}