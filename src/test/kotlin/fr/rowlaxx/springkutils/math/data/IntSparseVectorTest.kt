package fr.rowlaxx.springkutils.math.data

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.TreeMap

class IntSparseVectorTest {

    @Test
    fun testEmpty() {
        val empty = IntSparseVector.EMPTY
        assertEquals(0, empty.nonZeroCount())
        assertEquals(-1, empty.firstNonZeroIndex())
        assertEquals(-1, empty.lastNonZeroIndex())
        assertEquals(0, empty[0])
        assertEquals(0, empty[100])
        assertEquals(0, empty[-100])
        assertTrue(empty.content.isEmpty())
    }

    @Test
    fun testGet() {
        val v = MutableIntSparseVector()
        v[0] = 2
        v[10] = 5

        // Test 1: Existing index
        assertEquals(2, v[0])
        // Test 2: Non-existing index (should return 0)
        assertEquals(0, v[1])
        // Test 3: Large index
        assertEquals(5, v[10])
        // Test 4: Negative index
        v[-5] = 1
        assertEquals(1, v[-5])
    }

    @Test
    fun testGetAll() {
        val v = MutableIntSparseVector()
        v[1] = 1
        v[3] = 3

        // Test 1: Range with existing and non-existing indices
        assertEquals(listOf(0, 1, 0, 3, 0), v.getAll(0..4))
        // Test 2: Range with only zeros
        assertEquals(listOf(0, 0), v.getAll(5..6))
        // Test 3: Empty range
        assertTrue(v.getAll(5..4).isEmpty())
    }

    @Test
    fun testNonZeroCount() {
        val v = MutableIntSparseVector()
        // Test 1: Empty vector
        assertEquals(0, v.nonZeroCount())
        // Test 2: Vector with elements
        v[0] = 1
        v[5] = 2
        assertEquals(2, v.nonZeroCount())
        // Test 3: After removing elements (setting to zero)
        v[0] = 0
        assertEquals(1, v.nonZeroCount())
    }

    @Test
    fun testFirstZeroIndex() {
        val v = MutableIntSparseVector()
        // Test 1: Empty vector starts with 0
        assertEquals(0, v.firstZeroIndex())
        // Test 2: Filled beginning
        v[0] = 1
        v[1] = 1
        assertEquals(2, v.firstZeroIndex())
        // Test 3: Gap in the middle
        v[3] = 1
        assertEquals(2, v.firstZeroIndex())
    }

    @Test
    fun testLastZeroIndex() {
        val v = MutableIntSparseVector()
        // Test 1: Empty vector
        assertEquals(Int.MAX_VALUE, v.lastZeroIndex())
        // Test 2: Filled until MAX_VALUE - 1
        v[Int.MAX_VALUE - 1] = 1
        assertEquals(Int.MAX_VALUE, v.lastZeroIndex())
        // Test 3: MAX_VALUE is filled
        v[Int.MAX_VALUE] = 1
        assertEquals(Int.MAX_VALUE - 2, v.lastZeroIndex())
        
        // Test 4: More elements from the end
        v[Int.MAX_VALUE - 1] = 1
        v[Int.MAX_VALUE - 2] = 1
        assertEquals(Int.MAX_VALUE - 3, v.lastZeroIndex())
    }

    @Test
    fun testFirstNonZeroIndex() {
        val v = MutableIntSparseVector()
        // Test 1: Empty vector
        assertEquals(-1, v.firstNonZeroIndex())
        // Test 2: Positive index
        v[10] = 1
        assertEquals(10, v.firstNonZeroIndex())
        // Test 3: Negative index
        v[-5] = 2
        assertEquals(-5, v.firstNonZeroIndex())
    }

    @Test
    fun testLastNonZeroIndex() {
        val v = MutableIntSparseVector()
        // Test 1: Empty vector
        assertEquals(-1, v.lastNonZeroIndex())
        // Test 2: Positive index
        v[10] = 1
        assertEquals(10, v.lastNonZeroIndex())
        // Test 3: Higher index
        v[100] = 2
        assertEquals(100, v.lastNonZeroIndex())
    }

    @Test
    fun testNextNonZeroIndex() {
        val v = MutableIntSparseVector()
        v[10] = 1
        v[20] = 2

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
        val v = MutableIntSparseVector()
        v[10] = 1
        v[20] = 2

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
        val v = MutableIntSparseVector()
        v[0] = 1
        v[1] = 1

        // Test 1: From before (at non-zero)
        assertEquals(2, v.nextZeroIndex(0))
        // Test 2: From exact zero
        assertEquals(3, v.nextZeroIndex(3))
        // Test 3: From edge of MAX_VALUE
        v[Int.MAX_VALUE] = 1
        assertEquals(-1, v.nextZeroIndex(Int.MAX_VALUE))
    }

    @Test
    fun testPreviousZeroIndex() {
        val v = MutableIntSparseVector()
        v[0] = 1
        v[-1] = 1

        // Test 1: From after (at non-zero)
        assertEquals(-2, v.previousZeroIndex(0))
        // Test 2: From exact zero
        assertEquals(-2, v.previousZeroIndex(-2))
        // Test 3: From positive side
        assertEquals(1, v.previousZeroIndex(1))
        // Test 4: From edge of MIN_VALUE
        v[Int.MIN_VALUE] = 1
        assertEquals(-1, v.previousZeroIndex(Int.MIN_VALUE))
    }

    @Test
    fun testDot() {
        val v1 = MutableIntSparseVector()
        v1[0] = 2
        v1[1] = 3

        val v2 = MutableIntSparseVector()
        v2[0] = 4
        v2[2] = 5

        // Test 1: Overlapping and non-overlapping
        val dot1 = v1 dot v2
        assertEquals(8, dot1[0])
        assertEquals(0, dot1[1])
        assertEquals(0, dot1[2])
        assertEquals(1, dot1.nonZeroCount())

        // Test 2: Empty vector dot anything
        val dot2 = v1 dot IntSparseVector()
        assertEquals(0, dot2.nonZeroCount())

        // Test 3: Self dot (squared values)
        val dot3 = v1 dot v1
        assertEquals(4, dot3[0])
        assertEquals(9, dot3[1])
    }

    @Test
    fun testNorm() {
        // Test 1: Standard vector
        val v1 = MutableIntSparseVector()
        v1[0] = 3
        v1[1] = 4
        assertEquals(5, v1.norm())

        // Test 2: Empty vector
        assertEquals(0, IntSparseVector().norm())

        // Test 3: Single element
        val v2 = MutableIntSparseVector()
        v2[10] = -7
        assertEquals(7, v2.norm())
    }

    @Test
    fun testNormalized() {
        // Test 1: Standard vector
        val v1 = MutableIntSparseVector()
        v1[0] = 10
        v1[1] = 0
        val n1 = v1.normalized()
        assertEquals(1, n1[0])
        assertEquals(0, n1[1])
        assertEquals(1, n1.norm())

        // Test 2: Zero vector (should throw exception)
        assertThrows(IllegalStateException::class.java) {
            IntSparseVector().normalized()
        }
    }

    @Test
    fun testPlus() {
        val v1 = MutableIntSparseVector()
        v1[0] = 2
        v1[1] = 4

        val v2 = MutableIntSparseVector()
        v2[1] = -4
        v2[2] = 5

        // Test 1: Standard addition (with cancellation)
        val sum1 = v1 + v2
        assertEquals(2, sum1[0])
        assertEquals(0, sum1[1])
        assertEquals(5, sum1[2])
        assertEquals(2, sum1.nonZeroCount())

        // Test 2: Plus empty
        assertEquals(v1, v1 + IntSparseVector())

        // Test 3: Plus self
        val sum2 = v1 + v1
        assertEquals(4, sum2[0])
        assertEquals(8, sum2[1])
    }

    @Test
    fun testMinus() {
        val v1 = MutableIntSparseVector()
        v1[0] = 2
        v1[1] = 4

        val v2 = MutableIntSparseVector()
        v2[1] = 4
        v2[2] = 5

        // Test 1: Standard subtraction (with cancellation)
        val diff1 = v1 - v2
        assertEquals(2, diff1[0])
        assertEquals(0, diff1[1])
        assertEquals(-5, diff1[2])
        assertEquals(2, diff1.nonZeroCount())

        // Test 2: Minus self (zero vector)
        assertEquals(0, (v1 - v1).nonZeroCount())

        // Test 3: Minus empty
        assertEquals(v1, v1 - IntSparseVector())
    }

    @Test
    fun testAbs() {
        val v = MutableIntSparseVector()
        v[0] = -1
        v[1] = 2
        v[2] = 0

        // Test 1: Standard use
        val a1 = v.abs()
        assertEquals(1, a1[0])
        assertEquals(2, a1[1])

        // Test 2: Already positive
        assertEquals(a1, a1.abs())

        // Test 3: Empty vector
        assertEquals(0, IntSparseVector().abs().nonZeroCount())
    }

    @Test
    fun testDistance() {
        val v1 = MutableIntSparseVector()
        v1[0] = 3
        v1[1] = 4

        // Test 1: Distance to origin
        assertEquals(5, v1.distance(IntSparseVector()))

        // Test 2: Distance to self
        assertEquals(0, v1.distance(v1))

        // Test 3: Distance between two points
        val v2 = MutableIntSparseVector()
        v2[0] = 0
        v2[1] = 0
        assertEquals(5, v1.distance(v2))
        
        val v3 = MutableIntSparseVector()
        v3[0] = 3
        v3[1] = 0
        assertEquals(4, v1.distance(v3))
    }

    @Test
    fun testMultiplied() {
        val v = MutableIntSparseVector()
        v[0] = 2
        v[1] = 4

        // Test 1: Positive scalar
        val v1 = v multiplied 2
        assertEquals(4, v1[0])
        assertEquals(8, v1[1])

        // Test 2: Zero scalar (returns empty vector)
        assertEquals(0, (v multiplied 0).nonZeroCount())

        // Test 3: Negative scalar
        val v2 = v multiplied -1
        assertEquals(-2, v2[0])
        assertEquals(-4, v2[1])
    }

    @Test
    fun testDivided() {
        val v = MutableIntSparseVector()
        v[0] = 2
        v[1] = 4

        // Test 1: Standard division
        val v1 = v divided 2
        assertEquals(1, v1[0])
        assertEquals(2, v1[1])

        // Test 2: Division by one
        assertEquals(v, v divided 1)

        // Test 3: Division by zero (throws exception)
        assertThrows(IllegalArgumentException::class.java) {
            v divided 0
        }
    }

    @Test
    fun testForEachNonZero() {
        val v = MutableIntSparseVector()
        v[1] = 10
        v[3] = 30
        val visited = mutableMapOf<Int, Int>()

        // Test 1: Basic traversal
        v.forEachNonZero { i, d -> visited[i] = d }
        assertEquals(2, visited.size)
        assertEquals(10, visited[1])
        assertEquals(30, visited[3])

        // Test 2: Empty vector
        visited.clear()
        IntSparseVector().forEachNonZero { i, d -> visited[i] = d }
        assertTrue(visited.isEmpty())

        // Test 3: Modification during traversal
        var sum = 0
        v.forEachNonZero { _, d -> sum += d }
        assertEquals(40, sum)
    }

    @Test
    fun testForEach() {
        val v = MutableIntSparseVector()
        v[1] = 10
        val visited = mutableMapOf<Int, Int>()

        // Test 1: Range including non-zero
        v.forEach(0..2) { i, d -> visited[i] = d }
        assertEquals(3, visited.size)
        assertEquals(0, visited[0])
        assertEquals(10, visited[1])
        assertEquals(0, visited[2])

        // Test 2: Range with only zeros
        visited.clear()
        v.forEach(5..6) { i, d -> visited[i] = d }
        assertEquals(2, visited.size)
        assertEquals(0, visited[5])
        assertEquals(0, visited[6])

        // Test 3: Single element range
        visited.clear()
        v.forEach(1..1) { i, d -> visited[i] = d }
        assertEquals(1, visited.size)
        assertEquals(10, visited[1])
    }

    @Test
    fun testEqualsAndHashCode() {
        val v1 = MutableIntSparseVector()
        v1[1] = 1
        val v2 = MutableIntSparseVector()
        v2[1] = 1
        val v3 = MutableIntSparseVector()
        v3[1] = 2

        // Test 1: Equal vectors
        assertEquals(v1, v2)
        assertEquals(v1.hashCode(), v2.hashCode())

        // Test 2: Different vectors
        assertNotEquals(v1, v3)
        assertNotEquals(v1, IntSparseVector())

        // Test 3: Different types
        assertNotEquals(v1, "not a vector")
    }

    @Test
    fun testToString() {
        val v = MutableIntSparseVector()
        // Test 1: Empty vector
        assertEquals("IntSparseVector({})", v.toString())

        // Test 2: Vector with elements
        v[1] = 2
        assertEquals("IntSparseVector({1=2})", v.toString())

        // Test 3: Multiple elements
        v[0] = 1
        assertEquals("IntSparseVector({0=1, 1=2})", v.toString())
    }

    @Test
    fun testImmutableView() {
        val v = MutableIntSparseVector()
        v[0] = 1
        
        // Test 1: From MutableIntSparseVector
        val view = v.immutableView()
        assertEquals(v, view)
        assertFalse(view is MutableIntSparseVector)
        
        // Test 2: Reflects changes in original
        v[1] = 2
        assertEquals(2, view[1])
        
        // Test 3: From IntSparseVector
        val immutable = IntSparseVector(TreeMap(mapOf(0 to 1)))
        val same = immutable.immutableView()
        assertSame(immutable, same)
    }

    @Test
    fun testImmutableCopy() {
        val v = MutableIntSparseVector()
        v[0] = 1
        
        // Test 1: Content matches
        val copy = v.immutableCopy()
        assertEquals(v, copy)
        assertFalse(copy is MutableIntSparseVector)
        
        // Test 2: Independent from original
        v[1] = 2
        assertEquals(0, copy[1])
        
        // Test 3: Empty vector copy
        val empty = IntSparseVector()
        assertEquals(empty, empty.immutableCopy())
    }

    @Test
    fun testCopy() {
        val v = IntSparseVector(TreeMap(mapOf(0 to 1)))
        
        // Test 1: Content matches
        val copy = v.copy()
        assertEquals(v, copy)
        assertTrue(copy is MutableIntSparseVector)
        
        // Test 2: Independent from original
        copy[1] = 2
        assertEquals(0, v[1])
        
        // Test 3: From MutableIntSparseVector
        val v2 = MutableIntSparseVector()
        v2[0] = 5
        val copy2 = v2.copy()
        assertEquals(v2, copy2)
        v2[0] = 10
        assertEquals(5, copy2[0])
    }

    @Test
    fun testCross() {
        // Test 1: Unit vectors X and Y
        val x = MutableIntSparseVector()
        x[0] = 1
        val y = MutableIntSparseVector()
        y[1] = 1
        val z = x cross y
        assertEquals(1, z[2])
        assertEquals(1, z.nonZeroCount())
        
        // Test 2: Arbitrary vectors
        val a = MutableIntSparseVector()
        a[0] = 1; a[1] = 2; a[2] = 3
        val b = MutableIntSparseVector()
        b[0] = 4; b[1] = 5; b[2] = 6
        // (2*6 - 3*5, 3*4 - 1*6, 1*5 - 2*4) = (12-15, 12-6, 5-8) = (-3, 6, -3)
        val result = a cross b
        assertEquals(-3, result[0])
        assertEquals(6, result[1])
        assertEquals(-3, result[2])
        
        // Test 3: Parallel vectors
        val p1 = MutableIntSparseVector()
        p1[0] = 1; p1[1] = 1; p1[2] = 1
        val p2 = MutableIntSparseVector()
        p2[0] = 2; p2[1] = 2; p2[2] = 2
        val zero = p1 cross p2
        assertEquals(0, zero.nonZeroCount())
    }

    @Test
    fun testSum() {
        val v = MutableIntSparseVector()
        // Test 1: Empty vector
        assertEquals(0, v.sum())

        // Test 2: Vector with elements
        v[0] = 1
        v[5] = 2
        v[10] = -5
        assertEquals(-2, v.sum())

        // Test 3: Vector with zero resulting sum
        v[15] = 2
        assertEquals(0, v.sum())
    }
}
