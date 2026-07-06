package fr.rowlaxx.springkutils.array

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Functional and edge-case coverage for [MutableLongLongEntangledArray], the all-primitive
 * `long -> long` twin of [MutableLongObjectEntangledArray]. Mirrors that class's test suite,
 * additionally exercising the primitive-only [MutableLongLongEntangledArray.getOrDefault] accessor
 * and reaching the private helpers via reflection.
 */
class MutableLongLongEntangledArrayTest {

    // ---- shared helpers --------------------------------------------------

    private fun MutableLongLongEntangledArray.keysInOrder(): List<Long> {
        val out = ArrayList<Long>(size)
        forEach { k, _ -> out.add(k) }
        return out
    }

    private fun MutableLongLongEntangledArray.valuesInOrder(): List<Long> {
        val out = ArrayList<Long>(size)
        forEach { _, v -> out.add(v) }
        return out
    }

    // Reflection accessors for private fields / methods.
    private fun field(a: MutableLongLongEntangledArray, name: String): Any? {
        val f = MutableLongLongEntangledArray::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(a)
    }

    private fun capacity(a: MutableLongLongEntangledArray): Int = (field(a, "keys") as LongArray).size
    private fun startOf(a: MutableLongLongEntangledArray): Int = field(a, "start") as Int
    private fun endOf(a: MutableLongLongEntangledArray): Int = field(a, "end") as Int
    private fun keysArray(a: MutableLongLongEntangledArray): LongArray = field(a, "keys") as LongArray

    private fun invoke(a: MutableLongLongEntangledArray, name: String, vararg argTypes: Class<*>): java.lang.reflect.Method =
        MutableLongLongEntangledArray::class.java.getDeclaredMethod(name, *argTypes).apply { isAccessible = true }

    private fun search(a: MutableLongLongEntangledArray, key: Long): Int =
        invoke(a, "search", Long::class.javaPrimitiveType!!).invoke(a, key) as Int

    private fun lowerBound(a: MutableLongLongEntangledArray, key: Long): Int =
        invoke(a, "lowerBound", Long::class.javaPrimitiveType!!).invoke(a, key) as Int

    private fun upperBound(a: MutableLongLongEntangledArray, key: Long): Int =
        invoke(a, "upperBound", Long::class.javaPrimitiveType!!).invoke(a, key) as Int

    private fun ensureRoom(a: MutableLongLongEntangledArray) {
        invoke(a, "ensureRoom").invoke(a)
    }

    private fun ascending(a: MutableLongLongEntangledArray): Boolean {
        val s = startOf(a); val e = endOf(a); val ks = keysArray(a)
        for (i in s + 1 until e) if (ks[i] <= ks[i - 1]) return false
        return true
    }

    // =====================================================================
    // basics
    // =====================================================================

    @Test
    fun `empty array reports size 0 and misses every key`() {
        val a = MutableLongLongEntangledArray()
        assertEquals(0, a.size)
        assertTrue(a.isEmpty())
        assertNull(a[42L])
        assertEquals(-1L, a.getOrDefault(42L, -1L))
        assertFalse(a.containsKey(42L))
    }

    @Test
    fun `put then get round-trips`() {
        val a = MutableLongLongEntangledArray()
        assertNull(a.put(7L, 70L))
        assertEquals(70L, a[7L])
        assertEquals(70L, a.getOrDefault(7L, -1L))
        assertTrue(a.containsKey(7L))
        assertEquals(1, a.size)
    }

    @Test
    fun `put on existing key overwrites and returns the previous value`() {
        val a = MutableLongLongEntangledArray()
        a.put(7L, 1L)
        assertEquals(1L, a.put(7L, 2L))
        assertEquals(2L, a[7L])
        assertEquals(1, a.size)
    }

    @Test
    fun `out-of-order inserts keep keys ascending`() {
        val a = MutableLongLongEntangledArray()
        listOf(5L, 1L, 9L, 3L, 7L).forEach { a.put(it, it * 10) }
        assertEquals(listOf(1L, 3L, 5L, 7L, 9L), a.keysInOrder())
        assertEquals(1L, a.firstKey)
        assertEquals(9L, a.lastKey)
        assertEquals(10L, a.first)
        assertEquals(90L, a.last)
    }

    @Test
    fun `getOrPut creates once and reuses afterwards`() {
        val a = MutableLongLongEntangledArray()
        var calls = 0
        val first = a.getOrPut(3L) { calls++; 300L }
        val second = a.getOrPut(3L) { calls++; 999L }
        assertEquals(300L, first)
        assertEquals(300L, second)
        assertEquals(1, calls)
    }

    @Test
    fun `getOrPut passes the key to the default and inserts in order`() {
        val a = MutableLongLongEntangledArray()
        a.put(1L, 1L); a.put(3L, 3L)
        assertEquals(20L, a.getOrPut(2L) { it * 10 })
        assertEquals(listOf(1L, 2L, 3L), a.keysInOrder())
    }

    @Test
    fun `remove front, middle and end all preserve order`() {
        val a = MutableLongLongEntangledArray()
        (0L..5L).forEach { a.put(it, it * 10) }
        assertEquals(0L, a.remove(0L))     // front → advances start
        assertEquals(30L, a.remove(3L))    // middle → shift
        assertEquals(50L, a.remove(5L))    // end
        assertNull(a.remove(99L))          // absent
        assertEquals(listOf(1L, 2L, 4L), a.keysInOrder())
    }

    @Test
    fun `front eviction then append reuses freed room without losing order`() {
        val a = MutableLongLongEntangledArray(4)
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
        val a = MutableLongLongEntangledArray(64)
        for (i in 0L until 500L) a.put(i, i * 10)
        assertEquals(500, a.size)
        for (i in 0L until 500L) assertEquals(i * 10, a[i])
        assertEquals((0L until 500L).toList(), a.keysInOrder())
    }

    @Test
    fun `handles negative and very large keys and values`() {
        val a = MutableLongLongEntangledArray()
        val keys = listOf(Long.MIN_VALUE, -1_000_000_000L, -5L, 0L, 5L, 1_000_000_000L, Long.MAX_VALUE)
        keys.forEach { a.put(it, it) }
        keys.forEach { assertEquals(it, a[it]) }
        assertNull(a[-999L])
        assertEquals(Long.MIN_VALUE, a.remove(Long.MIN_VALUE))
        assertEquals(Long.MAX_VALUE, a.remove(Long.MAX_VALUE))
    }

    @Test
    fun `put descending keeps ascending order`() {
        val a = MutableLongLongEntangledArray()
        for (i in 100L downTo 1L) a.put(i, i)
        assertEquals((1L..100L).toList(), a.keysInOrder())
    }

    // =====================================================================
    // getOrDefault
    // =====================================================================

    @Test
    fun `getOrDefault hits fast-path endpoints and misses cleanly`() {
        val a = MutableLongLongEntangledArray()
        (3L..9L).forEach { a.put(it, it * 100) }
        assertEquals(300L, a.getOrDefault(3L, -1L))   // first fast-path
        assertEquals(900L, a.getOrDefault(9L, -1L))   // last fast-path
        assertEquals(600L, a.getOrDefault(6L, -1L))   // binary search
        assertEquals(-1L, a.getOrDefault(2L, -1L))    // below all
        assertEquals(-1L, a.getOrDefault(10L, -1L))   // above all
    }

    @Test
    fun `getOrDefault distinguishes a stored value equal to the default`() {
        val a = MutableLongLongEntangledArray()
        a.put(5L, 0L)
        assertEquals(0L, a.getOrDefault(5L, 0L))      // present, value happens to equal default
        assertTrue(a.containsKey(5L))
        assertFalse(a.containsKey(6L))
    }

    // =====================================================================
    // removeIf / clear
    // =====================================================================

    @Test
    fun `removeIf drops matching entries and compacts survivors in order`() {
        val a = MutableLongLongEntangledArray()
        for (i in 0L until 10L) a.put(i, i)
        a.removeIf { k, _ -> k % 2L == 0L }
        assertEquals(listOf(1L, 3L, 5L, 7L, 9L), a.keysInOrder())
        assertEquals(5, a.size)
        assertNull(a[4L])
        assertEquals(7L, a[7L])
        assertTrue(ascending(a))
        assertEquals(0, startOf(a))
        assertEquals(5, endOf(a))
    }

    @Test
    fun `removeIf sees the stored value`() {
        val a = MutableLongLongEntangledArray()
        for (i in 0L until 5L) a.put(i, i * 11)
        val seen = ArrayList<Pair<Long, Long>>()
        a.removeIf { k, v -> seen.add(k to v); false }
        assertEquals((0L until 5L).map { it to it * 11 }, seen)
    }

    @Test
    fun `removeIf with advanced start compacts to front`() {
        val a = MutableLongLongEntangledArray()
        for (i in 0L until 10L) a.put(i, i)
        a.remove(0L); a.remove(1L)   // advance start
        assertTrue(startOf(a) > 0)
        a.removeIf { k, _ -> k % 2L == 0L }
        assertEquals(listOf(3L, 5L, 7L, 9L), a.keysInOrder())
    }

    @Test
    fun `removeIf clearing everything resets to empty and stays usable`() {
        val a = MutableLongLongEntangledArray()
        for (i in 0L until 10L) a.put(i, i)
        a.removeIf { _, _ -> true }
        assertEquals(0, a.size)
        assertTrue(a.isEmpty())
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
        a.put(123L, 1230L)
        assertEquals(1230L, a[123L])
        assertEquals(1, a.size)
    }

    @Test
    fun `clear empties then stays usable`() {
        val a = MutableLongLongEntangledArray()
        for (i in 0L until 10L) a.put(i, i)
        a.clear()
        assertEquals(0, a.size)
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
        assertNull(a.firstOrNull)
        assertNull(a.lastOrNull)
        a.put(1L, 11L)
        assertEquals(11L, a[1L])
    }

    // =====================================================================
    // range / navigation queries
    // =====================================================================

    @Test
    fun `forEach visits in ascending order`() {
        val a = MutableLongLongEntangledArray()
        listOf(5L, 1L, 3L, 9L, 7L).forEach { a.put(it, it * 100) }
        val seen = ArrayList<Pair<Long, Long>>()
        a.forEach { k, v -> seen.add(k to v) }
        assertEquals(listOf(1L to 100L, 3L to 300L, 5L to 500L, 7L to 700L, 9L to 900L), seen)
    }

    @Test
    fun `forEachAfter respects inclusivity`() {
        val a = MutableLongLongEntangledArray()
        for (i in 0L until 10L) a.put(i, i)
        val excl = ArrayList<Long>(); val incl = ArrayList<Long>()
        a.forEachAfter(5L, inclusive = false) { k, _ -> excl.add(k) }
        a.forEachAfter(5L, inclusive = true) { k, _ -> incl.add(k) }
        assertEquals(listOf(6L, 7L, 8L, 9L), excl)
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L), incl)
    }

    @Test
    fun `forEachBefore respects inclusivity`() {
        val a = MutableLongLongEntangledArray()
        for (i in 0L until 10L) a.put(i, i)
        val excl = ArrayList<Long>(); val incl = ArrayList<Long>()
        a.forEachBefore(5L, inclusive = false) { k, _ -> excl.add(k) }
        a.forEachBefore(5L, inclusive = true) { k, _ -> incl.add(k) }
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), excl)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), incl)
    }

    @Test
    fun `forEachBefore and forEachAfter partition around a missing key`() {
        val a = MutableLongLongEntangledArray()
        listOf(10L, 20L, 30L, 40L).forEach { a.put(it, it) }
        val before = ArrayList<Long>(); val after = ArrayList<Long>()
        a.forEachBefore(25L, inclusive = false) { k, _ -> before.add(k) }
        a.forEachAfter(25L, inclusive = false) { k, _ -> after.add(k) }
        assertEquals(listOf(10L, 20L), before)
        assertEquals(listOf(30L, 40L), after)
    }

    @Test
    fun `forEachInRange visits the half-open lower, closed upper window`() {
        val a = MutableLongLongEntangledArray()
        for (i in 0L until 10L) a.put(i, i * 2)
        val seen = ArrayList<Pair<Long, Long>>()
        a.forEachInRange(fromExclusive = 3L, toInclusive = 6L) { k, v -> seen.add(k to v) }
        assertEquals(listOf(4L to 8L, 5L to 10L, 6L to 12L), seen)
    }

    @Test
    fun `countBefore and countAfter on present, absent and out-of-range keys`() {
        val a = MutableLongLongEntangledArray()
        for (i in 0L until 10L) a.put(i, i)
        assertEquals(4, a.countAfter(5L, inclusive = false))
        assertEquals(5, a.countAfter(5L, inclusive = true))
        assertEquals(5, a.countBefore(5L, inclusive = false))
        assertEquals(6, a.countBefore(5L, inclusive = true))
        assertEquals(0, a.countBefore(-1L, inclusive = true))
        assertEquals(0, a.countAfter(100L, inclusive = true))
    }

    @Test
    fun `lower returns the greatest strictly-smaller value or null`() {
        val a = MutableLongLongEntangledArray()
        listOf(10L, 20L, 30L).forEach { a.put(it, it * 5) }
        assertEquals(100L, a.lower(25L))   // greatest key below 25 is 20 → 100
        assertEquals(50L, a.lower(20L))    // strictly-lower neighbour
        assertNull(a.lower(10L))           // nothing below smallest
        assertEquals(150L, a.lower(100L))  // above all → last
        assertNull(MutableLongLongEntangledArray().lower(5L))
    }

    @Test
    fun `ceilingKey returns the smallest key at or above, or null`() {
        val a = MutableLongLongEntangledArray()
        listOf(10L, 20L, 30L).forEach { a.put(it, it) }
        assertEquals(20L, a.ceilingKey(15L))
        assertEquals(20L, a.ceilingKey(20L))
        assertEquals(10L, a.ceilingKey(Long.MIN_VALUE))
        assertNull(a.ceilingKey(31L))
    }

    // =====================================================================
    // private helpers via reflection
    // =====================================================================

    @Test
    fun `search encodes hits as index and misses as insertion point`() {
        val a = MutableLongLongEntangledArray()
        listOf(10L, 20L, 30L).forEach { a.put(it, it) }
        assertEquals(1, search(a, 20L))
        assertEquals(-1, search(a, 5L))    // insertion 0
        assertEquals(-3, search(a, 25L))   // insertion 2
        assertEquals(-4, search(a, 100L))  // insertion 3
    }

    @Test
    fun `lowerBound and upperBound bracket a present key`() {
        val a = MutableLongLongEntangledArray()
        for (i in 0L until 10L) a.put(i, i)
        assertEquals(5, lowerBound(a, 5L))
        assertEquals(6, upperBound(a, 5L))
        assertEquals(endOf(a), lowerBound(a, 100L))
        assertEquals(startOf(a), upperBound(a, -1L))
    }

    @Test
    fun `ensureRoom compacts to front instead of growing when start advanced`() {
        val a = MutableLongLongEntangledArray(4)
        for (i in 0L until 4L) a.put(i, i)
        a.remove(0L)                        // start=1, end=4==cap
        val cap = capacity(a)
        ensureRoom(a)
        assertEquals(cap, capacity(a))      // compacted, not doubled
        assertEquals(0, startOf(a))
        assertEquals(3, endOf(a))
        assertEquals(listOf(1L, 2L, 3L), a.keysInOrder())
    }

    @Test
    fun `ensureRoom doubles capacity when full and compacted`() {
        val a = MutableLongLongEntangledArray(4)
        for (i in 0L until 4L) a.put(i, i)
        assertEquals(4, capacity(a))
        ensureRoom(a)
        assertEquals(8, capacity(a))
        assertEquals(listOf(0L, 1L, 2L, 3L), a.keysInOrder())
    }

    // =====================================================================
    // integration: churn against a reference model
    // =====================================================================

    @Test
    fun `sliding window churn stays correct and ascending throughout`() {
        val window = 16L
        val base = 5_000_000_000L // far outside Long cache
        val a = MutableLongLongEntangledArray(8)
        for (i in 0L until window) a.put(base + i, base + i)

        var lo = base
        var hi = base + window
        repeat(5000) {
            a.put(hi, hi)
            a.remove(lo)
            hi++; lo++
            assertEquals(window.toInt(), a.size)
            assertTrue(ascending(a))
            assertEquals(lo, a.firstKey)
            assertEquals(hi - 1, a.lastKey)
            assertEquals(lo, a[lo])
            assertEquals(hi - 1, a[hi - 1])
            assertNull(a[lo - 1])
        }
        assertEquals((lo until hi).toList(), a.keysInOrder())
    }

    @Test
    fun `mixed random churn matches a sortedMap reference`() {
        val a = MutableLongLongEntangledArray(8)
        val model = sortedMapOf<Long, Long>()
        val rnd = java.util.Random(99)
        repeat(3000) {
            when (rnd.nextInt(4)) {
                0, 1 -> { val k = rnd.nextLong() % 2000; a.put(k, k * 3); model[k] = k * 3 }
                2 -> if (model.isNotEmpty()) {
                    val k = model.keys.toList()[rnd.nextInt(model.size)]
                    assertEquals(model.remove(k), a.remove(k))
                }
                else -> {
                    val threshold = rnd.nextLong() % 2000
                    a.removeIf { k, _ -> k < threshold }
                    model.keys.removeIf { it < threshold }
                }
            }
        }
        assertEquals(model.keys.toList(), a.keysInOrder())
        assertEquals(model.values.toList(), a.valuesInOrder())
        model.forEach { (k, v) -> assertEquals(v, a[k]) }
    }
}
