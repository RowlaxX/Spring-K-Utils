package fr.rowlaxx.springkutils.math.utils

import fr.rowlaxx.springkutils.math.data.MutableIntSparseVector
import fr.rowlaxx.springkutils.math.data.IntSparseVector
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IntSparseVectorUtilsTest {

    @Test
    fun testMin() {
        val v1 = MutableIntSparseVector()
        v1[0] = 5
        v1[1] = 10
        
        val v2 = MutableIntSparseVector()
        v2[0] = 10
        v2[1] = 5
        v2[2] = 2
        
        val res = IntSparseVectorUtils.min(v1, v2)
        assertEquals(5, res[0])
        assertEquals(5, res[1])
        assertEquals(0, res[2])
    }

    @Test
    fun testMax() {
        val v1 = MutableIntSparseVector()
        v1[0] = 5
        v1[1] = 10
        
        val v2 = MutableIntSparseVector()
        v2[0] = 10
        v2[1] = 5
        v2[2] = 2
        
        val res = IntSparseVectorUtils.max(v1, v2)
        assertEquals(10, res[0])
        assertEquals(10, res[1])
        assertEquals(2, res[2])
    }

    @Test
    fun testSerialization() {
        val v = MutableIntSparseVector()
        v[0] = 10
        v[5] = -5
        v[100] = 123456
        
        val bytes = IntSparseVectorUtils.serialize(v)
        val v2 = IntSparseVectorUtils.deserialize(bytes)
        
        assertEquals(v, v2)
        assertTrue(v2 is MutableIntSparseVector)
    }

    @Test
    fun testEmptySerialization() {
        val v = IntSparseVector()
        val bytes = IntSparseVectorUtils.serialize(v)
        val v2 = IntSparseVectorUtils.deserialize(bytes)
        
        assertEquals(v, v2)
        assertEquals(0, v2.nonZeroCount())
    }
}
