package fr.rowlaxx.springkutils.math.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MutableSparseVectorTest {

    @Test
    fun testSet() {
        val v = MutableSparseVector()
        // Test 1: Set non-zero value
        v[0] = 1.0
        assertEquals(1.0, v[0])
        // Test 2: Set zero value (should remove entry)
        v[0] = 0.0
        assertEquals(0.0, v[0])
        assertEquals(0, v.nonZeroCount())
        // Test 3: Set negative index
        v[-10] = 2.0
        assertEquals(2.0, v[-10])
    }

    @Test
    fun testSetAll() {
        val v = MutableSparseVector()
        // Test 1: Set range to non-zero
        v.setAll(0..2, 1.0)
        assertEquals(1.0, v[0])
        assertEquals(1.0, v[1])
        assertEquals(1.0, v[2])
        // Test 2: Set range to zero (should remove)
        v.setAll(1..2, 0.0)
        assertEquals(1.0, v[0])
        assertEquals(0.0, v[1])
        assertEquals(0, v.nonZeroCount() - 1)
        // Test 3: Large range
        v.setAll(100..200, 5.0)
        assertEquals(5.0, v[150])
        assertEquals(101, v.nonZeroCount() - 1)
    }

    @Test
    fun testAdd() {
        val v = MutableSparseVector()
        // Test 1: Add to zero
        v.add(0, 1.0)
        assertEquals(1.0, v[0])
        // Test 2: Add to non-zero
        v.add(0, 2.0)
        assertEquals(3.0, v[0])
        // Test 3: Add resulting in zero
        v.add(0, -3.0)
        assertEquals(0.0, v[0])
        assertEquals(0, v.nonZeroCount())
    }

    @Test
    fun testAddAll() {
        val v = MutableSparseVector()
        // Test 1: Add to zeros
        v.addAll(0..2, 1.0)
        assertEquals(1.0, v[1])
        // Test 2: Add to non-zeros
        v.addAll(1..2, 2.0)
        assertEquals(1.0, v[0])
        assertEquals(3.0, v[1])
        assertEquals(3.0, v[2])
        // Test 3: Add resulting in zeros
        v.addAll(0..2, -1.0)
        assertEquals(0.0, v[0])
        assertEquals(2.0, v[1])
    }

    @Test
    fun testSub() {
        val v = MutableSparseVector()
        // Test 1: Sub from zero
        v.sub(0, 1.0)
        assertEquals(-1.0, v[0])
        // Test 2: Sub from non-zero
        v.sub(0, 2.0)
        assertEquals(-3.0, v[0])
        // Test 3: Sub resulting in zero
        v.sub(0, -3.0)
        assertEquals(0.0, v[0])
    }

    @Test
    fun testSubAll() {
        val v = MutableSparseVector()
        // Test 1: Sub from zeros
        v.subAll(0..2, 1.0)
        assertEquals(-1.0, v[1])
        // Test 2: Sub from non-zeros
        v.subAll(1..2, 2.0)
        assertEquals(-1.0, v[0])
        assertEquals(-3.0, v[1])
        assertEquals(-3.0, v[2])
        // Test 3: Sub resulting in zeros
        v.subAll(0..2, -1.0)
        assertEquals(0.0, v[0])
        assertEquals(-2.0, v[1])
    }

    @Test
    fun testMultiply() {
        val v = MutableSparseVector()
        v[0] = 2.0
        v[1] = 4.0
        
        // Test 1: Standard multiplication
        v.multiply(2.0)
        assertEquals(4.0, v[0])
        assertEquals(8.0, v[1])

        // Test 2: Multiplication by zero
        v.multiply(0.0)
        assertEquals(0, v.nonZeroCount())

        // Test 3: Multiplication by one
        v[0] = 1.0
        v.multiply(1.0)
        assertEquals(1.0, v[0])
    }

    @Test
    fun testDivide() {
        val v = MutableSparseVector()
        v[0] = 2.0
        v[1] = 4.0
        
        // Test 1: Standard division
        v.divide(2.0)
        assertEquals(1.0, v[0])
        assertEquals(2.0, v[1])

        // Test 2: Division by one
        v.divide(1.0)
        assertEquals(1.0, v[0])

        // Test 3: Division by zero
        assertThrows(IllegalArgumentException::class.java) {
            v.divide(0.0)
        }
    }

    @Test
    fun testAddVector() {
        val v1 = MutableSparseVector()
        v1[0] = 1.0
        v1[1] = 2.0

        val v2 = MutableSparseVector()
        v2[1] = 3.0
        v2[2] = 4.0

        // Test 1: Add overlapping vectors
        v1.add(v2)
        assertEquals(1.0, v1[0])
        assertEquals(5.0, v1[1])
        assertEquals(4.0, v1[2])

        // Test 2: Add empty vector
        v1.add(SparseVector())
        assertEquals(5.0, v1[1])

        // Test 3: Add vector resulting in zero
        val v3 = MutableSparseVector()
        v3[0] = -1.0
        v1.add(v3)
        assertEquals(0.0, v1[0])
    }

    @Test
    fun testSubVector() {
        val v1 = MutableSparseVector()
        v1[0] = 1.0
        v1[1] = 2.0

        val v2 = MutableSparseVector()
        v2[1] = 3.0
        v2[2] = 4.0

        // Test 1: Sub overlapping vectors
        v1.sub(v2)
        assertEquals(1.0, v1[0])
        assertEquals(-1.0, v1[1])
        assertEquals(-4.0, v1[2])

        // Test 2: Sub empty vector
        v1.sub(SparseVector())
        assertEquals(-1.0, v1[1])

        // Test 3: Sub vector resulting in zero
        val v3 = MutableSparseVector()
        v3[0] = 1.0
        v1.sub(v3)
        assertEquals(0.0, v1[0])
    }

    @Test
    fun testTransformNonZero() {
        val v = MutableSparseVector()
        v[0] = 1.0
        v[1] = 2.0
        v[2] = 3.0

        // Test 1: Simple transformation
        v.transformNonZero { _, value -> value * 2.0 }
        assertEquals(2.0, v[0])
        assertEquals(4.0, v[1])
        assertEquals(6.0, v[2])

        // Test 2: Transformation to zero
        v.transformNonZero { _, value -> if (value == 2.0) 0.0 else value }
        assertEquals(0.0, v[0])
        assertEquals(2, v.nonZeroCount())

        // Test 3: Transformation using index
        v.transformNonZero { index, value -> value + index }
        assertEquals(4.0 + 1, v[1])
    }

    @Test
    fun testTransform() {
        val v = MutableSparseVector()
        v[0] = 1.0
        v[1] = 2.0

        // Test 1: Transform range with non-zeros
        v.transform(0..1) { _, value -> value - 2.0 }
        assertEquals(-1.0, v[0])
        assertEquals(0.0, v[1])
        assertEquals(1, v.nonZeroCount())

        // Test 2: Transform range with zeros
        v.transform(10..11) { _, value -> value + 5.0 }
        assertEquals(5.0, v[10])
        assertEquals(5.0, v[11])

        // Test 3: Single element transformation
        v.transform(0..0) { _, value -> value * -1 }
        assertEquals(1.0, v[0])
    }

    @Test
    fun testToSparseVector() {
        val mv = MutableSparseVector()
        mv[1] = 1.0
        // Test 1: Correct conversion
        val v = mv.toSparseVector()
        assertEquals(mv, v)
        
        // Test 2: Independent instance
        mv[2] = 2.0
        assertNotEquals(mv, v)
        
        // Test 3: Empty vector
        val emptyV = MutableSparseVector().toSparseVector()
        assertEquals(0, emptyV.nonZeroCount())
    }
}
