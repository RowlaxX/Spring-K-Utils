package fr.rowlaxx.springkutils.math.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MutableIntSparseVectorTest {

    @Test
    fun testSet() {
        val v = MutableIntSparseVector()
        // Test 1: Set non-zero value
        v[0] = 1
        assertEquals(1, v[0])
        // Test 2: Set zero value (should remove entry)
        v[0] = 0
        assertEquals(0, v[0])
        assertEquals(0, v.nonZeroCount())
        // Test 3: Set negative index
        v[-10] = 2
        assertEquals(2, v[-10])
    }

    @Test
    fun testSetAll() {
        val v = MutableIntSparseVector()
        // Test 1: Set range to non-zero
        v.setAll(0..2, 1)
        assertEquals(1, v[0])
        assertEquals(1, v[1])
        assertEquals(1, v[2])
        // Test 2: Set range to zero (should remove)
        v.setAll(1..2, 0)
        assertEquals(1, v[0])
        assertEquals(0, v[1])
        assertEquals(1, v.nonZeroCount())
        // Test 3: Large range
        v.setAll(100..200, 5)
        assertEquals(5, v[150])
        assertEquals(102, v.nonZeroCount())
    }

    @Test
    fun testAdd() {
        val v = MutableIntSparseVector()
        // Test 1: Add to zero
        v.add(0, 1)
        assertEquals(1, v[0])
        // Test 2: Add to non-zero
        v.add(0, 2)
        assertEquals(3, v[0])
        // Test 3: Add resulting in zero
        v.add(0, -3)
        assertEquals(0, v[0])
        assertEquals(0, v.nonZeroCount())
    }

    @Test
    fun testAddAll() {
        val v = MutableIntSparseVector()
        // Test 1: Add to zeros
        v.addAll(0..2, 1)
        assertEquals(1, v[1])
        // Test 2: Add to non-zeros
        v.addAll(1..2, 2)
        assertEquals(1, v[0])
        assertEquals(3, v[1])
        assertEquals(3, v[2])
        // Test 3: Add resulting in zeros
        v.addAll(0..2, -1)
        assertEquals(0, v[0])
        assertEquals(2, v[1])
    }

    @Test
    fun testSub() {
        val v = MutableIntSparseVector()
        // Test 1: Sub from zero
        v.sub(0, 1)
        assertEquals(-1, v[0])
        // Test 2: Sub from non-zero
        v.sub(0, 2)
        assertEquals(-3, v[0])
        // Test 3: Sub resulting in zero
        v.sub(0, -3)
        assertEquals(0, v[0])
    }

    @Test
    fun testSubAll() {
        val v = MutableIntSparseVector()
        // Test 1: Sub from zeros
        v.subAll(0..2, 1)
        assertEquals(-1, v[1])
        // Test 2: Sub from non-zeros
        v.subAll(1..2, 2)
        assertEquals(-1, v[0])
        assertEquals(-3, v[1])
        assertEquals(-3, v[2])
        // Test 3: Sub resulting in zeros
        v.subAll(0..2, -1)
        assertEquals(0, v[0])
        assertEquals(-2, v[1])
    }

    @Test
    fun testMultiply() {
        val v = MutableIntSparseVector()
        v[0] = 2
        v[1] = 4
        
        // Test 1: Standard multiplication
        v.multiply(2)
        assertEquals(4, v[0])
        assertEquals(8, v[1])

        // Test 2: Multiplication by zero
        v.multiply(0)
        assertEquals(0, v.nonZeroCount())

        // Test 3: Multiplication by one
        v[0] = 1
        v.multiply(1)
        assertEquals(1, v[0])
    }

    @Test
    fun testDivide() {
        val v = MutableIntSparseVector()
        v[0] = 2
        v[1] = 4
        
        // Test 1: Standard division
        v.divide(2)
        assertEquals(1, v[0])
        assertEquals(2, v[1])

        // Test 2: Division by one
        v.divide(1)
        assertEquals(1, v[0])

        // Test 3: Division by zero
        assertThrows(IllegalArgumentException::class.java) {
            v.divide(0)
        }
        
        // Test 4: Division resulting in zero
        v[0] = 1
        v.divide(2)
        assertEquals(0, v[0])
        assertEquals(1, v.nonZeroCount()) // v[1] should be 1 after divide(2) since 2/2=1
    }

    @Test
    fun testAddVector() {
        val v1 = MutableIntSparseVector()
        v1[0] = 1
        v1[1] = 2

        val v2 = MutableIntSparseVector()
        v2[1] = 3
        v2[2] = 4

        // Test 1: Add overlapping vectors
        v1.add(v2)
        assertEquals(1, v1[0])
        assertEquals(5, v1[1])
        assertEquals(4, v1[2])

        // Test 2: Add empty vector
        v1.add(IntSparseVector())
        assertEquals(5, v1[1])

        // Test 3: Add vector resulting in zero
        val v3 = MutableIntSparseVector()
        v3[0] = -1
        v1.add(v3)
        assertEquals(0, v1[0])
    }

    @Test
    fun testSubVector() {
        val v1 = MutableIntSparseVector()
        v1[0] = 1
        v1[1] = 2

        val v2 = MutableIntSparseVector()
        v2[1] = 3
        v2[2] = 4

        // Test 1: Sub overlapping vectors
        v1.sub(v2)
        assertEquals(1, v1[0])
        assertEquals(-1, v1[1])
        assertEquals(-4, v1[2])

        // Test 2: Sub empty vector
        v1.sub(IntSparseVector())
        assertEquals(-1, v1[1])

        // Test 3: Sub vector resulting in zero
        val v3 = MutableIntSparseVector()
        v3[0] = 1
        v1.sub(v3)
        assertEquals(0, v1[0])
    }

    @Test
    fun testTransformNonZero() {
        val v = MutableIntSparseVector()
        v[0] = 1
        v[1] = 2
        v[2] = 3

        // Test 1: Simple transformation
        v.transformNonZero { _, value -> value * 2 }
        assertEquals(2, v[0])
        assertEquals(4, v[1])
        assertEquals(6, v[2])

        // Test 2: Transformation to zero
        v.transformNonZero { _, value -> if (value == 2) 0 else value }
        assertEquals(0, v[0])
        assertEquals(2, v.nonZeroCount())

        // Test 3: Transformation using index
        v.transformNonZero { index, value -> value + index }
        assertEquals(4 + 1, v[1])
    }

    @Test
    fun testTransform() {
        val v = MutableIntSparseVector()
        v[0] = 1
        v[1] = 2

        // Test 1: Transform range with non-zeros
        v.transform(0..1) { _, value -> value - 2 }
        assertEquals(-1, v[0])
        assertEquals(0, v[1])
        assertEquals(1, v.nonZeroCount())

        // Test 2: Transform range with zeros
        v.transform(10..11) { _, value -> value + 5 }
        assertEquals(5, v[10])
        assertEquals(5, v[11])

        // Test 3: Single element transformation
        v.transform(0..0) { _, value -> value * -1 }
        assertEquals(1, v[0])
    }

    @Test
    fun testToIntSparseVector() {
        val mv = MutableIntSparseVector()
        mv[1] = 1
        // Test 1: Correct conversion
        val v = mv.toIntSparseVector()
        assertEquals(mv, v)
        
        // Test 2: Independent instance
        mv[2] = 2
        assertNotEquals(mv, v)
        
        // Test 3: Empty vector
        val emptyV = MutableIntSparseVector().toIntSparseVector()
        assertEquals(0, emptyV.nonZeroCount())
    }
}
