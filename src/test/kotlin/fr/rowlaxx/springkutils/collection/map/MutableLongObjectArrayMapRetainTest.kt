package fr.rowlaxx.springkutils.collection.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

/**
 * Covers the index-based bulk trims [MutableLongObjectArrayMap.retainFirst], [retainLast] and
 * `retain(range)`: they keep the first/last/`[first, last]` entries and drop everything else, moving
 * the live `[start, end)` window without copying.
 */
class MutableLongObjectArrayMapRetainTest {

    // ---- reflection accessors for the private backing state ---------------

    private fun field(a: MutableLongObjectArrayMap<*>, name: String): Any? {
        val f = MutableLongObjectArrayMap::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(a)
    }

    private fun capacity(a: MutableLongObjectArrayMap<*>): Int = (field(a, "keys") as LongArray).size
    private fun valuesArray(a: MutableLongObjectArrayMap<*>): Array<*> = field(a, "values") as Array<*>
    private fun startOf(a: MutableLongObjectArrayMap<*>): Int = field(a, "start") as Int
    private fun endOf(a: MutableLongObjectArrayMap<*>): Int = field(a, "end") as Int

    private fun filled(capacity: Int = 64, count: Int): MutableLongObjectArrayMap<String> {
        val a = MutableLongObjectArrayMap<String>(capacity)
        for (k in 0 until count) a.put(k.toLong(), "v$k")
        return a
    }

    private fun keysInOrder(a: MutableLongObjectArrayMap<*>): List<Long> {
        val out = ArrayList<Long>(a.size)
        a.forEach { k, _ -> out.add(k) }
        return out
    }

    private fun valuesInOrder(a: MutableLongObjectArrayMap<*>): List<String> {
        val out = ArrayList<String>(a.size)
        @Suppress("UNCHECKED_CAST")
        a.forEach { _, v -> out.add(v as String) }
        return out
    }

    /** Every value slot outside the live `[start, end)` window must be null so nothing is pinned. */
    private fun assertNoPinnedSlots(a: MutableLongObjectArrayMap<*>) {
        val values = valuesArray(a)
        for (i in values.indices) {
            if (i < startOf(a) || i >= endOf(a)) {
                assertNull(values[i], "slot $i still pinned a reference")
            }
        }
    }

    // ---- retainFirst ------------------------------------------------------

    @Test
    fun `retainFirst keeps the lowest n keys`() {
        val a = filled(count = 10)
        a.retainFirst(3)

        assertEquals(3, a.size)
        assertEquals(listOf(0L, 1L, 2L), keysInOrder(a))
        assertEquals(listOf("v0", "v1", "v2"), valuesInOrder(a))
        assertNull(a[3L])
        assertNoPinnedSlots(a)
    }

    @Test
    fun `retainFirst of zero empties the map`() {
        val a = filled(count = 5)
        a.retainFirst(0)

        assertTrue(a.isEmpty())
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
        assertNoPinnedSlots(a)
    }

    @Test
    fun `retainFirst at or above size is a no-op`() {
        val a = filled(capacity = 100, count = 50)
        a.retainFirst(50)
        a.retainFirst(999)

        assertEquals(50, a.size)
        assertEquals(100, capacity(a))
        assertEquals((0L until 50L).toList(), keysInOrder(a))
    }

    @Test
    fun `retainFirst works with an advanced start`() {
        val a = filled(count = 10)
        a.remove(0L); a.remove(1L) // start now points at key 2

        a.retainFirst(3)
        assertEquals(listOf(2L, 3L, 4L), keysInOrder(a))
        assertEquals(listOf("v2", "v3", "v4"), valuesInOrder(a))
    }

    // ---- retainLast -------------------------------------------------------

    @Test
    fun `retainLast keeps the highest n keys`() {
        val a = filled(count = 10)
        a.retainLast(3)

        assertEquals(3, a.size)
        assertEquals(listOf(7L, 8L, 9L), keysInOrder(a))
        assertEquals(listOf("v7", "v8", "v9"), valuesInOrder(a))
        assertNull(a[6L])
        assertNoPinnedSlots(a)
    }

    @Test
    fun `retainLast of zero empties the map`() {
        val a = filled(count = 5)
        a.retainLast(0)

        assertTrue(a.isEmpty())
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
        assertNoPinnedSlots(a)
    }

    @Test
    fun `retainLast at or above size is a no-op`() {
        val a = filled(capacity = 100, count = 50)
        a.retainLast(50)
        a.retainLast(999)

        assertEquals(50, a.size)
        assertEquals(100, capacity(a))
        assertEquals((0L until 50L).toList(), keysInOrder(a))
    }

    @Test
    fun `retainLast works with an advanced start`() {
        val a = filled(count = 10)
        a.remove(0L); a.remove(1L) // start now points at key 2

        a.retainLast(3)
        assertEquals(listOf(7L, 8L, 9L), keysInOrder(a))
        assertEquals(listOf("v7", "v8", "v9"), valuesInOrder(a))
    }

    // ---- retain(range) ----------------------------------------------------

    @Test
    fun `retain keeps a middle index range`() {
        val a = filled(count = 10)
        a.retain(3..6)

        assertEquals(listOf(3L, 4L, 5L, 6L), keysInOrder(a))
        assertEquals(listOf("v3", "v4", "v5", "v6"), valuesInOrder(a))
        assertNull(a[2L]); assertNull(a[7L])
        assertNoPinnedSlots(a)
    }

    @Test
    fun `retain prefix range keeps the first entries`() {
        val a = filled(count = 10)
        a.retain(0..4)
        assertEquals((0L..4L).toList(), keysInOrder(a))
    }

    @Test
    fun `retain suffix range keeps the last entries`() {
        val a = filled(count = 10)
        a.retain(7..9)
        assertEquals(listOf(7L, 8L, 9L), keysInOrder(a))
    }

    @Test
    fun `retain clamps an over-wide range to everything`() {
        val a = filled(capacity = 100, count = 50)
        a.retain(-10..1000)

        assertEquals(50, a.size)
        assertEquals(100, capacity(a))
        assertEquals((0L until 50L).toList(), keysInOrder(a))
    }

    @Test
    fun `retain clamps negative and over-large bounds independently`() {
        val a = filled(count = 10)
        a.retain(-5..2)
        assertEquals(listOf(0L, 1L, 2L), keysInOrder(a))

        val b = filled(count = 10)
        b.retain(7..50)
        assertEquals(listOf(7L, 8L, 9L), keysInOrder(b))
    }

    @Test
    fun `retain with a fully out-of-bounds or empty range clears`() {
        filled(count = 10).also { it.retain(20..30); assertTrue(it.isEmpty()) }
        filled(count = 10).also { it.retain(5..2); assertTrue(it.isEmpty()) }
        filled(count = 10).also { it.retain(-5..-1); assertTrue(it.isEmpty()) }
    }

    @Test
    fun `retain on a map with an advanced start keeps the right window`() {
        val a = filled(count = 10)
        a.remove(0L); a.remove(1L) // start now points at key 2

        a.retain(2..4) // logical positions 2..4 -> keys 4,5,6
        assertEquals(listOf(4L, 5L, 6L), keysInOrder(a))
    }

    // ---- side effects and integration -------------------------------------

    @Test
    fun `a retained map still accepts puts and removes`() {
        val a = filled(count = 10)
        a.retain(3..6)

        a.put(100L, "new")
        assertEquals("new", a[100L])
        assertEquals("v4", a.remove(4L))
        assertNull(a[4L])
        assertEquals(listOf(3L, 5L, 6L, 100L), keysInOrder(a))
    }

    @Test
    fun `retain nulls the freed value slots and releases capacity on collapse`() {
        val a = filled(capacity = 512, count = 400)
        a.retain(100..199)

        assertEquals(100, a.size)
        assertEquals((100L until 200L).toList(), keysInOrder(a))
        assertTrue(capacity(a) < 512, "capacity ${capacity(a)} was not released")
        assertNoPinnedSlots(a)
    }

    @Test
    fun `retain matching a reference list across random windows and churn`() {
        val a = MutableLongObjectArrayMap<String>(8)
        var model = ArrayList<Pair<Long, String>>()
        val rnd = Random(20240918)

        repeat(4000) {
            when (rnd.nextInt(7)) {
                0, 1 -> {
                    val k = rnd.nextLong() % 300
                    val v = "v$k"
                    a.put(k, v)
                    model.removeIf { it.first == k }
                    model.add(k to v)
                    model.sortBy { it.first }
                }
                2 -> {
                    if (model.isNotEmpty()) {
                        val k = model[rnd.nextInt(model.size)].first
                        val expected = model.first { it.first == k }.second
                        assertEquals(expected, a.remove(k))
                        model.removeIf { it.first == k }
                    }
                }
                3 -> {
                    val n = rnd.nextInt(model.size + 3) - 1
                    a.retainFirst(n)
                    model = ArrayList(model.take(n.coerceAtLeast(0).coerceAtMost(model.size)))
                }
                4 -> {
                    val n = rnd.nextInt(model.size + 3) - 1
                    a.retainLast(n)
                    model = ArrayList(model.takeLast(n.coerceAtLeast(0).coerceAtMost(model.size)))
                }
                else -> {
                    val lo = rnd.nextInt(model.size + 4) - 2
                    val hi = rnd.nextInt(model.size + 4) - 2
                    a.retain(lo..hi)
                    model = ArrayList(retainSlice(model, lo, hi))
                }
            }

            assertEquals(model.map { it.first }, keysInOrder(a))
            assertEquals(model.map { it.second }, valuesInOrder(a))
            model.forEach { (k, v) -> assertEquals(v, a[k]) }
            assertNoPinnedSlots(a)
        }
    }

    private fun retainSlice(
        list: List<Pair<Long, String>>,
        lo: Int,
        hi: Int
    ): List<Pair<Long, String>> {
        val from = lo.coerceAtLeast(0)
        val to = hi.coerceAtMost(list.size - 1)
        return if (from > to) emptyList() else list.subList(from, to + 1).toList()
    }
}
