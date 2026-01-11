package fr.rowlaxx.springkutils.math.utils

import fr.rowlaxx.springkutils.math.data.MutableSparseVector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SparseVectorUtilsTest {

    @Test
    fun testMinMax() {
        val v1 = MutableSparseVector()
        v1[0] = 2.0
        v1[1] = 5.0
        v1[2] = -1.0

        val v2 = MutableSparseVector()
        v2[0] = 3.0
        v2[1] = 4.0
        v2[3] = 1.0

        val minVec = SparseVectorUtils.min(v1, v2)
        assertEquals(2.0, minVec[0])
        assertEquals(4.0, minVec[1])
        assertEquals(-1.0, minVec[2])
        assertEquals(0.0, minVec[3])

        val maxVec = SparseVectorUtils.max(v1, v2)
        assertEquals(3.0, maxVec[0])
        assertEquals(5.0, maxVec[1])
        assertEquals(0.0, maxVec[2])
        assertEquals(1.0, maxVec[3])
    }

    @Test
    fun `test empty vector serialization`() {
        val vector = MutableSparseVector()
        val bytes = SparseVectorUtils.serialize(vector)
        val deserialized = SparseVectorUtils.deserialize(bytes)
        assertEquals(vector, deserialized)
        assertEquals(0, deserialized.nonZeroCount())
    }

    @Test
    fun `test single element vector serialization`() {
        val vector = MutableSparseVector()
        vector[0] = 123.456
        val bytes = SparseVectorUtils.serialize(vector)
        val deserialized = SparseVectorUtils.deserialize(bytes)
        assertEquals(vector, deserialized)
        assertEquals(123.456, deserialized[0])
    }

    @Test
    fun `test multiple elements vector serialization`() {
        val vector = MutableSparseVector()
        vector[10] = 1.0
        vector[20] = -2.5
        vector[100] = 1000.0
        val bytes = SparseVectorUtils.serialize(vector)
        val deserialized = SparseVectorUtils.deserialize(bytes)
        assertEquals(vector, deserialized)
        assertEquals(1.0, deserialized[10])
        assertEquals(-2.5, deserialized[20])
        assertEquals(1000.0, deserialized[100])
    }

    @Test
    fun `test vector with extreme values serialization`() {
        val vector = MutableSparseVector()
        vector[1] = Double.MAX_VALUE
        vector[2] = Double.MIN_VALUE
        vector[3] = Double.POSITIVE_INFINITY
        vector[4] = Double.NEGATIVE_INFINITY
        vector[5] = Double.NaN
        val bytes = SparseVectorUtils.serialize(vector)
        val deserialized = SparseVectorUtils.deserialize(bytes)
        // Note: assertEquals(Double.NaN, Double.NaN) is false in some cases but usually true in JUnit
        assertEquals(vector.nonZeroCount(), deserialized.nonZeroCount())
        assertEquals(Double.MAX_VALUE, deserialized[1])
        assertEquals(Double.MIN_VALUE, deserialized[2])
        assertEquals(Double.POSITIVE_INFINITY, deserialized[3])
        assertEquals(Double.NEGATIVE_INFINITY, deserialized[4])
        assertTrue(deserialized[5].isNaN())
    }

    @Test
    fun `test large vector serialization`() {
        val vector = MutableSparseVector()
        for (i in 1..1000) {
            vector[i * 10] = i.toDouble()
        }
        val bytes = SparseVectorUtils.serialize(vector)
        val deserialized = SparseVectorUtils.deserialize(bytes)
        assertEquals(vector, deserialized)
        assertEquals(1000, deserialized.nonZeroCount())
        for (i in 1..1000) {
            assertEquals(i.toDouble(), deserialized[i * 10])
        }
    }
}
