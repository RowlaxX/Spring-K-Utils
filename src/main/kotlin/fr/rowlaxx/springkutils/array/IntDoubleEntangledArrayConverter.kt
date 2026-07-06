package fr.rowlaxx.springkutils.array

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = true)
class IntDoubleEntangledArrayConverter : AttributeConverter<IntDoubleEntangledArray?, ByteArray?> {

    override fun convertToDatabaseColumn(attribute: IntDoubleEntangledArray?): ByteArray? {
        return attribute?.serialize()
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): IntDoubleEntangledArray? {
        return dbData?.let { IntDoubleEntangledArray.deserialize(it) }
    }

}