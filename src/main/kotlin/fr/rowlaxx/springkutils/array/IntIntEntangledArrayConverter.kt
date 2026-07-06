package fr.rowlaxx.springkutils.array

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

@Converter(autoApply = true)
class IntIntEntangledArrayConverter : AttributeConverter<IntIntEntangledArray?, ByteArray?> {

    override fun convertToDatabaseColumn(attribute: IntIntEntangledArray?): ByteArray? {
        return attribute?.serialize()
    }

    override fun convertToEntityAttribute(dbData: ByteArray?): IntIntEntangledArray? {
        return dbData?.let { IntIntEntangledArray.deserialize(it) }
    }

}