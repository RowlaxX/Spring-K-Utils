package fr.rowlaxx.springkutils.array

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MutableLongObjectEntangledArrayTest {

    private fun <V> MutableLongObjectEntangledArray<V>.keysInOrder(): List<Long> {
        val out = ArrayList<Long>(size)
        forEach { k, _ -> out.add(k) }
        return out
    }

    @Test
    fun `empty array reports size 0 and misses every key`() {
        val a = MutableLongObjectEntangledArray<String>()
        assertEquals(0, a.size)
        assertTrue(a.isEmpty)
        assertNull(a[42L])
        assertFalse(a.containsKey(42L))
    }

    @Test
    fun `put then get round-trips`() {
        val a = MutableLongObjectEntangledArray<String>()
        assertNull(a.put(7L, "v7"))
        assertEquals("v7", a[7L])
        assertTrue(a.containsKey(7L))
        assertEquals(1, a.size)
    }

    @Test
    fun `put on existing key overwrites and returns the previous value`() {
        val a = MutableLongObjectEntangledArray<String>()
        a.put(7L, "old")
        assertEquals("old", a.put(7L, "new"))
        assertEquals("new", a[7L])
        assertEquals(1, a.size)
    }

    @Test
    fun `out-of-order inserts keep keys ascending`() {
        val a = MutableLongObjectEntangledArray<String>()
        listOf(5L, 1L, 9L, 3L, 7L).forEach { a.put(it, "v$it") }
        assertEquals(listOf(1L, 3L, 5L, 7L, 9L), a.keysInOrder())
        assertEquals(1L, a.firstKey)
        assertEquals(9L, a.lastKey)
    }

    @Test
    fun `getOrPut creates once and reuses afterwards`() {
        val a = MutableLongObjectEntangledArray<Any>()
        var calls = 0
        val first = a.getOrPut(3L) { calls++; Any() }
        val second = a.getOrPut(3L) { calls++; Any() }
        assertSame(first, second)
        assertEquals(1, calls)
    }

    @Test
    fun `remove front, middle and end all preserve order`() {
        val a = MutableLongObjectEntangledArray<String>()
        (0L..5L).forEach { a.put(it, "v$it") }
        assertEquals("v0", a.remove(0L))   // front → advances start
        assertEquals("v3", a.remove(3L))   // middle → shift
        assertEquals("v5", a.remove(5L))   // end
        assertNull(a.remove(99L))          // absent
        assertEquals(listOf(1L, 2L, 4L), a.keysInOrder())
    }

    @Test
    fun `front eviction then append reuses freed room without losing order`() {
        val a = MutableLongObjectEntangledArray<Long>(4)
        // sliding window: append a new highest key, drop the lowest
        var lo = 0L
        var hi = 0L
        repeat(3) { a.put(hi, hi); hi++ }
        repeat(1000) {
            a.put(hi, hi); hi++
            a.remove(lo); lo++
        }
        assertEquals(3, a.size)
        assertEquals(listOf(hi - 3, hi - 2, hi - 1), a.keysInOrder())
        assertEquals(hi - 2, a[hi - 2])
    }

    @Test
    fun `grows past the initial capacity and keeps every entry`() {
        val a = MutableLongObjectEntangledArray<Long>(64)
        for (i in 0L until 500L) a.put(i, i * 10)
        assertEquals(500, a.size)
        for (i in 0L until 500L) assertEquals(i * 10, a[i])
        assertEquals((0L until 500L).toList(), a.keysInOrder())
    }

    @Test
    fun `removeIf drops matching entries and compacts survivors in order`() {
        val a = MutableLongObjectEntangledArray<Long>()
        for (i in 0L until 10L) a.put(i, i)
        a.removeIf { k, _ -> k % 2L == 0L }
        assertEquals(listOf(1L, 3L, 5L, 7L, 9L), a.keysInOrder())
        assertEquals(5, a.size)
        assertNull(a[4L])
        assertEquals(7L, a[7L])
    }

    @Test
    fun `removeIf clearing everything resets to empty and stays usable`() {
        val a = MutableLongObjectEntangledArray<Long>()
        for (i in 0L until 10L) a.put(i, i)
        a.removeIf { _, _ -> true }
        assertEquals(0, a.size)
        assertTrue(a.isEmpty)
        a.put(123L, 123L)
        assertEquals(123L, a[123L])
        assertEquals(1, a.size)
    }
}
