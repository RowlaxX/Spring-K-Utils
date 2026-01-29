package fr.rowlaxx.springkutils.math.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.TreeMap

class SparseVectorTest {

    @Test
    fun testEmpty() {
        val empty = SparseVector.EMPTY
        assertEquals(0, empty.nonZeroCount())
        assertEquals(-1, empty.firstNonZeroIndex())
        assertEquals(-1, empty.lastNonZeroIndex())
        assertEquals(0.0, empty[0])
        assertEquals(0.0, empty[100])
        assertEquals(0.0, empty[-100])
        assertTrue(empty.content.isEmpty())
    }

    @Test
    fun testGet() {
        val v = MutableSparseVector()
        v[0] = 2.0
        v[10] = 5.0

        // Test 1: Existing index
        assertEquals(2.0, v[0])
        // Test 2: Non-existing index (should return 0.0)
        assertEquals(0.0, v[1])
        // Test 3: Large index
        assertEquals(5.0, v[10])
        // Test 4: Negative index
        v[-5] = 1.0
        assertEquals(1.0, v[-5])
    }

    @Test
    fun testGetAll() {
        val v = MutableSparseVector()
        v[1] = 1.0
        v[3] = 3.0

        // Test 1: Range with existing and non-existing indices
        assertEquals(listOf(0.0, 1.0, 0.0, 3.0, 0.0), v.getAll(0..4))
        // Test 2: Range with only zeros
        assertEquals(listOf(0.0, 0.0), v.getAll(5..6))
        // Test 3: Empty range
        assertTrue(v.getAll(5..4).isEmpty())
    }

    @Test
    fun testNonZeroCount() {
        val v = MutableSparseVector()
        // Test 1: Empty vector
        assertEquals(0, v.nonZeroCount())
        // Test 2: Vector with elements
        v[0] = 1.0
        v[5] = 2.0
        assertEquals(2, v.nonZeroCount())
        // Test 3: After removing elements (setting to zero)
        v[0] = 0.0
        assertEquals(1, v.nonZeroCount())
    }

    @Test
    fun testFirstZeroIndex() {
        val v = MutableSparseVector()
        // Test 1: Empty vector starts with 0
        assertEquals(0, v.firstZeroIndex())
        // Test 2: Filled beginning
        v[0] = 1.0
        v[1] = 1.0
        assertEquals(2, v.firstZeroIndex())
        // Test 3: Gap in the middle
        v[3] = 1.0
        assertEquals(2, v.firstZeroIndex())
    }

    @Test
    fun testLastZeroIndex() {
        val v = MutableSparseVector()
        // Test 1: Empty vector
        assertEquals(Int.MAX_VALUE, v.lastZeroIndex())
        // Test 2: Filled until MAX_VALUE - 1
        v[Int.MAX_VALUE - 1] = 1.0
        assertEquals(Int.MAX_VALUE, v.lastZeroIndex())
        // Test 3: MAX_VALUE is filled
        v[Int.MAX_VALUE] = 1.0
        assertEquals(Int.MAX_VALUE - 2, v.lastZeroIndex())
        
        // Test 4: More elements from the end
        v[Int.MAX_VALUE - 1] = 1.0
        v[Int.MAX_VALUE - 2] = 1.0
        assertEquals(Int.MAX_VALUE - 3, v.lastZeroIndex())
    }

    @Test
    fun testFirstNonZeroIndex() {
        val v = MutableSparseVector()
        // Test 1: Empty vector
        assertEquals(-1, v.firstNonZeroIndex())
        // Test 2: Positive index
        v[10] = 1.0
        assertEquals(10, v.firstNonZeroIndex())
        // Test 3: Negative index
        v[-5] = 2.0
        assertEquals(-5, v.firstNonZeroIndex())
    }

    @Test
    fun testLastNonZeroIndex() {
        val v = MutableSparseVector()
        // Test 1: Empty vector
        assertEquals(-1, v.lastNonZeroIndex())
        // Test 2: Positive index
        v[10] = 1.0
        assertEquals(10, v.lastNonZeroIndex())
        // Test 3: Higher index
        v[100] = 2.0
        assertEquals(100, v.lastNonZeroIndex())
    }

    @Test
    fun testNextNonZeroIndex() {
        val v = MutableSparseVector()
        v[10] = 1.0
        v[20] = 2.0

        // Test 1: From before
        assertEquals(10, v.nextNonZeroIndex(0))
        // Test 2: From exact index
        assertEquals(10, v.nextNonZeroIndex(10))
        // Test 3: From between
        assertEquals(20, v.nextNonZeroIndex(11))
        // Test 4: From after last
        assertEquals(-1, v.nextNonZeroIndex(21))
    }

    @Test
    fun testPreviousNonZeroIndex() {
        val v = MutableSparseVector()
        v[10] = 1.0
        v[20] = 2.0

        // Test 1: From after
        assertEquals(20, v.previousNonZeroIndex(30))
        // Test 2: From exact index
        assertEquals(20, v.previousNonZeroIndex(20))
        // Test 3: From between
        assertEquals(10, v.previousNonZeroIndex(19))
        // Test 4: From before first
        assertEquals(-1, v.previousNonZeroIndex(9))
    }

    @Test
    fun testNextZeroIndex() {
        val v = MutableSparseVector()
        v[0] = 1.0
        v[1] = 1.0

        // Test 1: From before (at non-zero)
        assertEquals(2, v.nextZeroIndex(0))
        // Test 2: From exact zero
        assertEquals(3, v.nextZeroIndex(3))
        // Test 3: From edge of MAX_VALUE
        v[Int.MAX_VALUE] = 1.0
        assertEquals(-1, v.nextZeroIndex(Int.MAX_VALUE))
    }

    @Test
    fun testPreviousZeroIndex() {
        val v = MutableSparseVector()
        v[0] = 1.0
        v[-1] = 1.0

        // Test 1: From after (at non-zero)
        assertEquals(-2, v.previousZeroIndex(0))
        // Test 2: From exact zero
        assertEquals(-2, v.previousZeroIndex(-2))
        // Test 3: From positive side
        assertEquals(1, v.previousZeroIndex(1))
        // Test 4: From edge of MIN_VALUE
        v[Int.MIN_VALUE] = 1.0
        assertEquals(-1, v.previousZeroIndex(Int.MIN_VALUE))
    }

    @Test
    fun testDot() {
        val v1 = MutableSparseVector()
        v1[0] = 2.0
        v1[1] = 3.0

        val v2 = MutableSparseVector()
        v2[0] = 4.0
        v2[2] = 5.0

        // Test 1: Overlapping and non-overlapping
        val dot1 = v1 dot v2
        assertEquals(8.0, dot1[0])
        assertEquals(0.0, dot1[1])
        assertEquals(0.0, dot1[2])
        assertEquals(1, dot1.nonZeroCount())

        // Test 2: Empty vector dot anything
        val dot2 = v1 dot SparseVector()
        assertEquals(0, dot2.nonZeroCount())

        // Test 3: Self dot (squared values)
        val dot3 = v1 dot v1
        assertEquals(4.0, dot3[0])
        assertEquals(9.0, dot3[1])
    }

    @Test
    fun testNorm() {
        // Test 1: Standard vector
        val v1 = MutableSparseVector()
        v1[0] = 3.0
        v1[1] = 4.0
        assertEquals(5.0, v1.norm())

        // Test 2: Empty vector
        assertEquals(0.0, SparseVector().norm())

        // Test 3: Single element
        val v2 = MutableSparseVector()
        v2[10] = -7.0
        assertEquals(7.0, v2.norm())
    }

    @Test
    fun testNormalized() {
        // Test 1: Standard vector
        val v1 = MutableSparseVector()
        v1[0] = 3.0
        v1[1] = 4.0
        val n1 = v1.normalized()
        assertEquals(0.6, n1[0], 1e-9)
        assertEquals(0.8, n1[1], 1e-9)
        assertEquals(1.0, n1.norm(), 1e-9)

        // Test 2: Already normalized
        val n2 = n1.normalized()
        assertEquals(n1, n2)

        // Test 3: Zero vector (should throw exception)
        assertThrows(IllegalStateException::class.java) {
            SparseVector().normalized()
        }
    }

    @Test
    fun testPlus() {
        val v1 = MutableSparseVector()
        v1[0] = 2.0
        v1[1] = 4.0

        val v2 = MutableSparseVector()
        v2[1] = -4.0
        v2[2] = 5.0

        // Test 1: Standard addition (with cancellation)
        val sum1 = v1 + v2
        assertEquals(2.0, sum1[0])
        assertEquals(0.0, sum1[1])
        assertEquals(5.0, sum1[2])
        assertEquals(2, sum1.nonZeroCount())

        // Test 2: Plus empty
        assertEquals(v1, v1 + SparseVector())

        // Test 3: Plus self
        val sum2 = v1 + v1
        assertEquals(4.0, sum2[0])
        assertEquals(8.0, sum2[1])
    }

    @Test
    fun testMinus() {
        val v1 = MutableSparseVector()
        v1[0] = 2.0
        v1[1] = 4.0

        val v2 = MutableSparseVector()
        v2[1] = 4.0
        v2[2] = 5.0

        // Test 1: Standard subtraction (with cancellation)
        val diff1 = v1 - v2
        assertEquals(2.0, diff1[0])
        assertEquals(0.0, diff1[1])
        assertEquals(-5.0, diff1[2])
        assertEquals(2, diff1.nonZeroCount())

        // Test 2: Minus self (zero vector)
        assertEquals(0, (v1 - v1).nonZeroCount())

        // Test 3: Minus empty
        assertEquals(v1, v1 - SparseVector())
    }

    @Test
    fun testAbs() {
        val v = MutableSparseVector()
        v[0] = -1.0
        v[1] = 2.0
        v[2] = 0.0

        // Test 1: Standard use
        val a1 = v.abs()
        assertEquals(1.0, a1[0])
        assertEquals(2.0, a1[1])

        // Test 2: Already positive
        assertEquals(a1, a1.abs())

        // Test 3: Empty vector
        assertEquals(0, SparseVector().abs().nonZeroCount())
    }

    @Test
    fun testDistance() {
        val v1 = MutableSparseVector()
        v1[0] = 3.0
        v1[1] = 4.0

        // Test 1: Distance to origin
        assertEquals(5.0, v1.distance(SparseVector()))

        // Test 2: Distance to self
        assertEquals(0.0, v1.distance(v1))

        // Test 3: Distance between two points
        val v2 = MutableSparseVector()
        v2[0] = 0.0
        v2[1] = 0.0
        assertEquals(5.0, v1.distance(v2))
        
        val v3 = MutableSparseVector()
        v3[0] = 3.0
        v3[1] = 0.0
        assertEquals(4.0, v1.distance(v3))
    }

    @Test
    fun testMultiplied() {
        val v = MutableSparseVector()
        v[0] = 2.0
        v[1] = 4.0

        // Test 1: Positive scalar
        val v1 = v multiplied 2.0
        assertEquals(4.0, v1[0])
        assertEquals(8.0, v1[1])

        // Test 2: Zero scalar (returns empty vector)
        assertEquals(0, (v multiplied 0.0).nonZeroCount())

        // Test 3: Negative scalar
        val v2 = v multiplied -1.0
        assertEquals(-2.0, v2[0])
        assertEquals(-4.0, v2[1])
    }

    @Test
    fun testDivided() {
        val v = MutableSparseVector()
        v[0] = 2.0
        v[1] = 4.0

        // Test 1: Standard division
        val v1 = v divided 2.0
        assertEquals(1.0, v1[0])
        assertEquals(2.0, v1[1])

        // Test 2: Division by one
        assertEquals(v, v divided 1.0)

        // Test 3: Division by zero (throws exception)
        assertThrows(IllegalArgumentException::class.java) {
            v divided 0.0
        }
    }

    @Test
    fun testForEachNonZero() {
        val v = MutableSparseVector()
        v[1] = 10.0
        v[3] = 30.0
        val visited = mutableMapOf<Int, Double>()

        // Test 1: Basic traversal
        v.forEachNonZero { i, d -> visited[i] = d }
        assertEquals(2, visited.size)
        assertEquals(10.0, visited[1])
        assertEquals(30.0, visited[3])

        // Test 2: Empty vector
        visited.clear()
        SparseVector().forEachNonZero { i, d -> visited[i] = d }
        assertTrue(visited.isEmpty())

        // Test 3: Modification during traversal (if supported by map, but here we just check access)
        var sum = 0.0
        v.forEachNonZero { _, d -> sum += d }
        assertEquals(40.0, sum)
    }

    @Test
    fun testForEach() {
        val v = MutableSparseVector()
        v[1] = 10.0
        val visited = mutableMapOf<Int, Double>()

        // Test 1: Range including non-zero
        v.forEach(0..2) { i, d -> visited[i] = d }
        assertEquals(3, visited.size)
        assertEquals(0.0, visited[0])
        assertEquals(10.0, visited[1])
        assertEquals(0.0, visited[2])

        // Test 2: Range with only zeros
        visited.clear()
        v.forEach(5..6) { i, d -> visited[i] = d }
        assertEquals(2, visited.size)
        assertEquals(0.0, visited[5])
        assertEquals(0.0, visited[6])

        // Test 3: Single element range
        visited.clear()
        v.forEach(1..1) { i, d -> visited[i] = d }
        assertEquals(1, visited.size)
        assertEquals(10.0, visited[1])
    }

    @Test
    fun testEqualsAndHashCode() {
        val v1 = MutableSparseVector()
        v1[1] = 1.0
        val v2 = MutableSparseVector()
        v2[1] = 1.0
        val v3 = MutableSparseVector()
        v3[1] = 2.0

        // Test 1: Equal vectors
        assertEquals(v1, v2)
        assertEquals(v1.hashCode(), v2.hashCode())

        // Test 2: Different vectors
        assertNotEquals(v1, v3)
        assertNotEquals(v1, SparseVector())

        // Test 3: Different types
        assertNotEquals(v1, "not a vector")
    }

    @Test
    fun testToString() {
        val v = MutableSparseVector()
        // Test 1: Empty vector
        assertEquals("SparseVector({})", v.toString())

        // Test 2: Vector with elements
        v[1] = 2.0
        assertEquals("SparseVector({1=2.0})", v.toString())

        // Test 3: Multiple elements
        v[0] = 1.0
        assertEquals("SparseVector({0=1.0, 1=2.0})", v.toString())
    }

    @Test
    fun testImmutableView() {
        val v = MutableSparseVector()
        v[0] = 1.0
        
        // Test 1: From MutableSparseVector
        val view = v.immutableView()
        assertEquals(v, view)
        assertFalse(view is MutableSparseVector)
        
        // Test 2: Reflects changes in original
        v[1] = 2.0
        assertEquals(2.0, view[1])
        
        // Test 3: From SparseVector
        val immutable = SparseVector(TreeMap(mapOf(0 to 1.0)))
        val same = immutable.immutableView()
        assertSame(immutable, same)
    }

    @Test
    fun testImmutableCopy() {
        val v = MutableSparseVector()
        v[0] = 1.0
        
        // Test 1: Content matches
        val copy = v.immutableCopy()
        assertEquals(v, copy)
        assertFalse(copy is MutableSparseVector)
        
        // Test 2: Independent from original
        v[1] = 2.0
        assertEquals(0.0, copy[1])
        
        // Test 3: Empty vector copy
        val empty = SparseVector()
        assertEquals(empty, empty.immutableCopy())
    }

    @Test
    fun testCopy() {
        val v = SparseVector(TreeMap(mapOf(0 to 1.0)))
        
        // Test 1: Content matches
        val copy = v.copy()
        assertEquals(v, copy)
        assertTrue(copy is MutableSparseVector)
        
        // Test 2: Independent from original
        copy[1] = 2.0
        assertEquals(0.0, v[1])
        
        // Test 3: From MutableSparseVector
        val v2 = MutableSparseVector()
        v2[0] = 5.0
        val copy2 = v2.copy()
        assertEquals(v2, copy2)
        v2[0] = 10.0
        assertEquals(5.0, copy2[0])
    }

    @Test
    fun testCross() {
        // Test 1: Unit vectors X and Y
        val x = MutableSparseVector()
        x[0] = 1.0
        val y = MutableSparseVector()
        y[1] = 1.0
        val z = x cross y
        assertEquals(1.0, z[2])
        assertEquals(1, z.nonZeroCount())
        
        // Test 2: Arbitrary vectors
        val a = MutableSparseVector()
        a[0] = 1.0; a[1] = 2.0; a[2] = 3.0
        val b = MutableSparseVector()
        b[0] = 4.0; b[1] = 5.0; b[2] = 6.0
        // (2*6 - 3*5, 3*4 - 1*6, 1*5 - 2*4) = (12-15, 12-6, 5-8) = (-3, 6, -3)
        val result = a cross b
        assertEquals(-3.0, result[0])
        assertEquals(6.0, result[1])
        assertEquals(-3.0, result[2])
        
        // Test 3: Parallel vectors
        val p1 = MutableSparseVector()
        p1[0] = 1.0; p1[1] = 1.0; p1[2] = 1.0
        val p2 = MutableSparseVector()
        p2[0] = 2.0; p2[1] = 2.0; p2[2] = 2.0
        val zero = p1 cross p2
        assertEquals(0, zero.nonZeroCount())
    }

    @Test
    fun testSum() {
        val v = MutableSparseVector()
        // Test 1: Empty vector
        assertEquals(0.0, v.sum())

        // Test 2: Vector with elements
        v[0] = 1.0
        v[5] = 2.0
        v[10] = -0.5
        assertEquals(2.5, v.sum())

        // Test 3: Vector with zero resulting sum
        v[15] = -2.5
        assertEquals(0.0, v.sum())
    }
}
