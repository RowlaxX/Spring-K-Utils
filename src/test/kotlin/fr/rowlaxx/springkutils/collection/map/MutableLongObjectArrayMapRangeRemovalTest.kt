package fr.rowlaxx.springkutils.collection.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the window-trimming removals of [MutableLongObjectArrayMap]: [MutableLongObjectArrayMap.removeBefore],
 * [MutableLongObjectArrayMap.removeAfter] and [MutableLongObjectArrayMap.removeRange], including their
 * inclusive/exclusive bounds, no-op edges, interaction with an advanced `start`, and the post-removal shrink.
 */
class MutableLongObjectArrayMapRangeRemovalTest {

    private fun <V> MutableLongObjectArrayMap<V>.keysInOrder(): List<Long> {
        val out = ArrayList<Long>(size)
        forEach { k, _ -> out.add(k) }
        return out
    }

    private fun field(a: MutableLongObjectArrayMap<*>, name: String): Any? {
        val f = MutableLongObjectArrayMap::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(a)
    }

    private fun capacity(a: MutableLongObjectArrayMap<*>) = (field(a, "keys") as LongArray).size
    private fun startOf(a: MutableLongObjectArrayMap<*>) = field(a, "start") as Int
    private fun endOf(a: MutableLongObjectArrayMap<*>) = field(a, "end") as Int
    private fun keysArray(a: MutableLongObjectArrayMap<*>) = field(a, "keys") as LongArray
    @Suppress("UNCHECKED_CAST")
    private fun valuesArray(a: MutableLongObjectArrayMap<*>) = field(a, "values") as Array<Any?>

    private fun filled(capacity: Int = 64, count: Int): MutableLongObjectArrayMap<String> {
        val a = MutableLongObjectArrayMap<String>(capacity)
        for (k in 0 until count) a.put(k.toLong(), "v$k")
        return a
    }

    // ---- removeBefore ----------------------------------------------------------------------

    @Test
    fun `removeBefore exclusive keeps the boundary key`() {
        val a = filled(count = 10)
        a.removeBefore(5L, inclusive = false)
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L), a.keysInOrder())
        assertEquals("v5", a[5L])
        assertNull(a[4L])
    }

    @Test
    fun `removeBefore inclusive drops the boundary key`() {
        val a = filled(count = 10)
        a.removeBefore(5L, inclusive = true)
        assertEquals(listOf(6L, 7L, 8L, 9L), a.keysInOrder())
        assertNull(a[5L])
    }

    @Test
    fun `removeBefore below every key is a no-op`() {
        val a = filled(count = 5)
        a.removeBefore(0L, inclusive = false)
        a.removeBefore(-100L, inclusive = true)
        assertEquals((0L until 5L).toList(), a.keysInOrder())
    }

    @Test
    fun `removeBefore above every key clears and resets indices`() {
        val a = filled(count = 5)
        a.removeBefore(100L, inclusive = true)
        assertEquals(0, a.size)
        assertTrue(a.isEmpty())
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
    }

    @Test
    fun `removeBefore on empty is a no-op`() {
        val a = MutableLongObjectArrayMap<String>()
        a.removeBefore(10L, inclusive = true)
        assertEquals(0, a.size)
    }

    @Test
    fun `removeBefore handles a missing boundary key`() {
        val a = MutableLongObjectArrayMap<String>()
        listOf(10L, 20L, 30L).forEach { a.put(it, "v$it") }
        a.removeBefore(25L, inclusive = true)
        assertEquals(listOf(30L), a.keysInOrder())
    }

    @Test
    fun `removeBefore with an advanced start leaves the survivors correct`() {
        val a = filled(count = 10)
        a.remove(0L); a.remove(1L); a.remove(2L) // advance start
        assertTrue(startOf(a) > 0)
        a.removeBefore(6L, inclusive = false)
        assertEquals(listOf(6L, 7L, 8L, 9L), a.keysInOrder())
    }

    // ---- removeAfter -----------------------------------------------------------------------

    @Test
    fun `removeAfter exclusive keeps the boundary key`() {
        val a = filled(count = 10)
        a.removeAfter(4L, inclusive = false)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), a.keysInOrder())
        assertEquals("v4", a[4L])
        assertNull(a[5L])
    }

    @Test
    fun `removeAfter inclusive drops the boundary key`() {
        val a = filled(count = 10)
        a.removeAfter(4L, inclusive = true)
        assertEquals(listOf(0L, 1L, 2L, 3L), a.keysInOrder())
        assertNull(a[4L])
    }

    @Test
    fun `removeAfter above every key is a no-op`() {
        val a = filled(count = 5)
        a.removeAfter(100L, inclusive = false)
        a.removeAfter(4L, inclusive = false)
        assertEquals((0L until 5L).toList(), a.keysInOrder())
    }

    @Test
    fun `removeAfter below every key clears and resets indices`() {
        val a = filled(count = 5)
        a.removeAfter(-1L, inclusive = true)
        assertEquals(0, a.size)
        assertTrue(a.isEmpty())
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
    }

    @Test
    fun `removeAfter on empty is a no-op`() {
        val a = MutableLongObjectArrayMap<String>()
        a.removeAfter(10L, inclusive = true)
        assertEquals(0, a.size)
    }

    @Test
    fun `removeAfter handles a missing boundary key`() {
        val a = MutableLongObjectArrayMap<String>()
        listOf(10L, 20L, 30L).forEach { a.put(it, "v$it") }
        a.removeAfter(25L, inclusive = false)
        assertEquals(listOf(10L, 20L), a.keysInOrder())
    }

    @Test
    fun `removeAfter with an advanced start leaves the survivors correct`() {
        val a = filled(count = 10)
        a.remove(0L); a.remove(1L) // advance start
        a.removeAfter(7L, inclusive = false)
        assertEquals(listOf(2L, 3L, 4L, 5L, 6L, 7L), a.keysInOrder())
    }

    // ---- removeRange -----------------------------------------------------------------------

    @Test
    fun `removeRange removes both inclusive bounds`() {
        val a = filled(count = 10)
        a.removeRange(3L..6L)
        assertEquals(listOf(0L, 1L, 2L, 7L, 8L, 9L), a.keysInOrder())
        assertNull(a[3L])
        assertNull(a[6L])
        assertEquals("v2", a[2L])
        assertEquals("v7", a[7L])
    }

    @Test
    fun `removeRange on a single key`() {
        val a = filled(count = 5)
        a.removeRange(2L..2L)
        assertEquals(listOf(0L, 1L, 3L, 4L), a.keysInOrder())
    }

    @Test
    fun `empty range is a no-op`() {
        val a = filled(count = 5)
        a.removeRange(3L..2L)
        assertEquals((0L until 5L).toList(), a.keysInOrder())
    }

    @Test
    fun `range that covers no stored key is a no-op`() {
        val a = MutableLongObjectArrayMap<String>()
        listOf(10L, 20L, 30L).forEach { a.put(it, "v$it") }
        a.removeRange(11L..19L)
        assertEquals(listOf(10L, 20L, 30L), a.keysInOrder())
    }

    @Test
    fun `range below and above all stored keys is a no-op`() {
        val a = filled(count = 5)
        a.removeRange(-100L..-1L)
        a.removeRange(100L..200L)
        assertEquals((0L until 5L).toList(), a.keysInOrder())
    }

    @Test
    fun `range covering everything clears and resets indices`() {
        val a = filled(count = 5)
        a.removeRange(0L..4L)
        a.removeRange(Long.MIN_VALUE..Long.MAX_VALUE)
        assertEquals(0, a.size)
        assertTrue(a.isEmpty())
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
    }

    @Test
    fun `removeRange spanning the whole key space clears`() {
        val a = filled(count = 5)
        a.removeRange(Long.MIN_VALUE..Long.MAX_VALUE)
        assertEquals(0, a.size)
    }

    @Test
    fun `removeRange preserves the values of the surviving tail and head`() {
        val a = filled(count = 10)
        a.removeRange(4L..7L)
        assertEquals(listOf(0L, 1L, 2L, 3L, 8L, 9L), a.keysInOrder())
        for (k in listOf(0L, 1L, 2L, 3L, 8L, 9L)) assertEquals("v$k", a[k])
    }

    @Test
    fun `removeRange with an advanced start shifts the tail correctly`() {
        val a = filled(count = 10)
        a.remove(0L); a.remove(1L); a.remove(2L) // advance start
        assertTrue(startOf(a) > 0)
        a.removeRange(5L..7L)
        assertEquals(listOf(3L, 4L, 8L, 9L), a.keysInOrder())
    }

    @Test
    fun `removeRange at the front equivalent to removeAfter`() {
        val a = filled(count = 10)
        a.removeRange(0L..4L)
        assertEquals(listOf(5L, 6L, 7L, 8L, 9L), a.keysInOrder())
    }

    @Test
    fun `removeRange at the back equivalent to removeBefore`() {
        val a = filled(count = 10)
        a.removeRange(6L..9L)
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), a.keysInOrder())
    }

    @Test
    fun `removeRange handles negative and large keys`() {
        val a = MutableLongObjectArrayMap<String>()
        val keys = listOf(Long.MIN_VALUE, -100L, -1L, 0L, 1L, 100L, Long.MAX_VALUE)
        keys.forEach { a.put(it, "v$it") }
        a.removeRange(-100L..100L)
        assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE), a.keysInOrder())
    }

    // ---- shrink ----------------------------------------------------------------------------

    @Test
    fun `removeBefore below 50 percent shrinks the backing arrays`() {
        val a = filled(capacity = 1000, count = 1000)
        a.removeBefore(800L, inclusive = false)
        assertEquals(200, a.size)
        assertTrue(capacity(a) < 1000, "capacity ${capacity(a)} was not released")
    }

    @Test
    fun `removeAfter below 50 percent shrinks the backing arrays`() {
        val a = filled(capacity = 1000, count = 1000)
        a.removeAfter(199L, inclusive = false)
        assertEquals(200, a.size)
        assertTrue(capacity(a) < 1000, "capacity ${capacity(a)} was not released")
    }

    @Test
    fun `removeRange collapsing to a handful releases the peak capacity`() {
        val a = filled(capacity = 512, count = 400)
        a.removeRange(8L..399L)
        assertEquals(8, a.size)
        assertEquals(16, capacity(a))
    }

    // ---- integration -----------------------------------------------------------------------

    @Test
    fun `sliding window via removeBefore matches a reference map`() {
        val a = MutableLongObjectArrayMap<String>(8)
        val model = sortedMapOf<Long, String>()
        for (i in 0L until 5L) { a.put(i, "v$i"); model[i] = "v$i" }

        var lo = 0L
        var hi = 5L
        repeat(2000) {
            a.put(hi, "v$hi"); model[hi] = "v$hi"
            hi++
            a.removeBefore(hi - 5L, inclusive = false)
            model.keys.removeIf { it < hi - 5L }
        }
        assertEquals(model.keys.toList(), a.keysInOrder())
        model.forEach { (k, v) -> assertEquals(v, a[k]) }
    }

    @Test
    fun `interleaved range removals and inserts stay consistent`() {
        val a = MutableLongObjectArrayMap<Long>()
        val model = sortedMapOf<Long, Long>()
        for (k in 0L until 200L) { a.put(k, k); model[k] = k }

        a.removeRange(10L..49L); model.keys.removeIf { it in 10L..49L }
        a.removeBefore(5L, inclusive = false); model.keys.removeIf { it < 5L }
        a.removeAfter(150L, inclusive = true); model.keys.removeIf { it >= 150L }
        for (k in 100L until 120L) { a.put(k, k * 10); model[k] = k * 10 }

        assertEquals(model.keys.toList(), a.keysInOrder())
        model.forEach { (k, v) -> assertEquals(v, a[k]) }
        assertFalse(a.isEmpty())
    }

    // ---- no-op paths must not touch the backing arrays ---------------------------------------

    @Test
    fun `removeBefore with no matching key neither copies nor shrinks`() {
        val a = filled(capacity = 16, count = 10)
        val keys = keysArray(a); val values = valuesArray(a)
        val s = startOf(a); val e = endOf(a)

        a.removeBefore(0L, inclusive = false) // nothing < 0
        a.removeBefore(-1L, inclusive = true) // nothing <= -1

        assertSame(keys, keysArray(a))
        assertSame(values, valuesArray(a))
        assertEquals(s, startOf(a))
        assertEquals(e, endOf(a))
        assertEquals(16, capacity(a))
        assertEquals((0L until 10L).toList(), a.keysInOrder())
    }

    @Test
    fun `removeAfter with no matching key neither copies nor shrinks`() {
        val a = filled(capacity = 16, count = 10)
        val keys = keysArray(a); val values = valuesArray(a)
        val s = startOf(a); val e = endOf(a)

        a.removeAfter(9L, inclusive = false) // nothing > 9
        a.removeAfter(100L, inclusive = false)

        assertSame(keys, keysArray(a))
        assertSame(values, valuesArray(a))
        assertEquals(s, startOf(a))
        assertEquals(e, endOf(a))
        assertEquals(16, capacity(a))
        assertEquals((0L until 10L).toList(), a.keysInOrder())
    }

    @Test
    fun `removeRange with no matching key neither copies nor shrinks`() {
        val a = filled(capacity = 16, count = 10)
        val keys = keysArray(a); val values = valuesArray(a)
        val s = startOf(a); val e = endOf(a)

        a.removeRange(100L..200L) // above all
        a.removeRange(-50L..-1L)  // below all
        a.removeRange(5L..4L)     // empty range

        assertSame(keys, keysArray(a))
        assertSame(values, valuesArray(a))
        assertEquals(s, startOf(a))
        assertEquals(e, endOf(a))
        assertEquals(16, capacity(a))
        assertEquals((0L until 10L).toList(), a.keysInOrder())
    }

    // ---- removeBefore: extra scenarios -------------------------------------------------------

    @Test
    fun `removeBefore prefix advances start in place without reallocation`() {
        val a = filled(capacity = 16, count = 10)
        val keys = keysArray(a)
        a.removeBefore(4L, inclusive = false)
        assertSame(keys, keysArray(a))
        assertEquals(4, startOf(a))
        assertEquals(10, endOf(a))
        assertEquals(4L, keysArray(a)[startOf(a)])
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L, 9L), a.keysInOrder())
    }

    @Test
    fun `removeBefore nulls exactly the freed value slots`() {
        val a = filled(capacity = 16, count = 10)
        a.removeBefore(5L, inclusive = false)
        val values = valuesArray(a)
        for (i in 0 until 5) assertNull(values[i])
        for (i in 5 until 10) assertEquals("v$i", values[i])
    }

    @Test
    fun `removeBefore can release capacity on a large collapse`() {
        val a = filled(capacity = 1000, count = 1000)
        a.removeBefore(900L, inclusive = false)
        assertEquals(100, a.size)
        assertTrue(capacity(a) < 1000)
    }

    @Test
    fun `removeBefore inclusive at Long MIN removes only that key`() {
        val a = MutableLongObjectArrayMap<String>()
        a.put(Long.MIN_VALUE, "min"); a.put(0L, "z"); a.put(Long.MAX_VALUE, "max")
        a.removeBefore(Long.MIN_VALUE, inclusive = true)
        assertEquals(listOf(0L, Long.MAX_VALUE), a.keysInOrder())
        assertNull(a[Long.MIN_VALUE])
    }

    @Test
    fun `removeBefore on a single element map`() {
        val a = filled(capacity = 16, count = 1)
        a.removeBefore(0L, inclusive = false)
        assertEquals(1, a.size)
        a.removeBefore(0L, inclusive = true)
        assertTrue(a.isEmpty())
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
    }

    @Test
    fun `removeBefore then inserts reuse the freed window`() {
        val a = filled(capacity = 16, count = 10)
        a.removeBefore(6L, inclusive = false) // keep 6..9
        a.put(-1L, "v-1"); a.put(10L, "v10"); a.put(7L, "v7b")
        assertEquals(listOf(-1L, 6L, 7L, 8L, 9L, 10L), a.keysInOrder())
        assertEquals("v7b", a[7L])
    }

    @Test
    fun `repeated removeBefore drains the map from the front`() {
        val a = filled(capacity = 16, count = 16)
        for (k in 0L until 16L) a.removeBefore(k, inclusive = true)
        assertTrue(a.isEmpty())
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
    }

    // ---- removeAfter: extra scenarios --------------------------------------------------------

    @Test
    fun `removeAfter suffix shrinks end in place without reallocation`() {
        val a = filled(capacity = 16, count = 10)
        val keys = keysArray(a)
        a.removeAfter(5L, inclusive = false)
        assertSame(keys, keysArray(a))
        assertEquals(0, startOf(a))
        assertEquals(6, endOf(a))
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), a.keysInOrder())
    }

    @Test
    fun `removeAfter nulls exactly the freed value slots`() {
        val a = filled(capacity = 16, count = 10)
        a.removeAfter(5L, inclusive = false)
        val values = valuesArray(a)
        for (i in 0..5) assertEquals("v$i", values[i])
        for (i in 6 until 10) assertNull(values[i])
    }

    @Test
    fun `removeAfter can release capacity on a large collapse`() {
        val a = filled(capacity = 1000, count = 1000)
        a.removeAfter(99L, inclusive = false)
        assertEquals(100, a.size)
        assertTrue(capacity(a) < 1000)
    }

    @Test
    fun `removeAfter inclusive at Long MAX removes only that key`() {
        val a = MutableLongObjectArrayMap<String>()
        a.put(Long.MIN_VALUE, "min"); a.put(0L, "z"); a.put(Long.MAX_VALUE, "max")
        a.removeAfter(Long.MAX_VALUE, inclusive = true)
        assertEquals(listOf(Long.MIN_VALUE, 0L), a.keysInOrder())
        assertNull(a[Long.MAX_VALUE])
    }

    @Test
    fun `removeAfter on a single element map`() {
        val a = filled(capacity = 16, count = 1)
        a.removeAfter(0L, inclusive = false)
        assertEquals(1, a.size)
        a.removeAfter(0L, inclusive = true)
        assertTrue(a.isEmpty())
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
    }

    @Test
    fun `removeAfter then inserts reuse the freed tail`() {
        val a = filled(capacity = 16, count = 10)
        a.removeAfter(3L, inclusive = false) // keep 0..3
        a.put(4L, "v4b"); a.put(100L, "v100")
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 100L), a.keysInOrder())
        assertEquals("v4b", a[4L])
    }

    @Test
    fun `repeated removeAfter drains the map from the back`() {
        val a = filled(capacity = 16, count = 16)
        for (k in 15L downTo 0L) a.removeAfter(k, inclusive = true)
        assertTrue(a.isEmpty())
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
    }

    // ---- removeRange: extra scenarios --------------------------------------------------------

    @Test
    fun `removeRange prefix advances start without copying`() {
        val a = filled(capacity = 16, count = 10)
        val keys = keysArray(a)
        a.removeRange(0L..3L)
        assertSame(keys, keysArray(a))
        assertEquals(4, startOf(a))
        assertEquals(10, endOf(a))
        assertEquals(4L, keysArray(a)[startOf(a)])
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L, 9L), a.keysInOrder())
    }

    @Test
    fun `removeRange suffix shrinks end without copying`() {
        val a = filled(capacity = 16, count = 10)
        val keys = keysArray(a)
        a.removeRange(6L..9L)
        assertSame(keys, keysArray(a))
        assertEquals(0, startOf(a))
        assertEquals(6, endOf(a))
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L, 5L), a.keysInOrder())
    }

    @Test
    fun `removeRange interior compacts the tail and keeps start`() {
        val a = filled(capacity = 16, count = 10)
        val keys = keysArray(a)
        a.removeRange(3L..6L)
        assertSame(keys, keysArray(a))
        assertEquals(0, startOf(a))
        assertEquals(6, endOf(a))
        assertEquals(listOf(0L, 1L, 2L, 7L, 8L, 9L), a.keysInOrder())
        assertEquals(7L, keysArray(a)[3])
    }

    @Test
    fun `removeRange covering the entire live window clears with no copy and nulls slots`() {
        val a = filled(capacity = 16, count = 10)
        val keys = keysArray(a)
        a.removeRange(0L..9L)
        assertSame(keys, keysArray(a))
        assertEquals(0, a.size)
        assertEquals(0, startOf(a))
        assertEquals(0, endOf(a))
        for (v in valuesArray(a)) assertNull(v)
    }

    @Test
    fun `removeRange only the first key`() {
        val a = filled(capacity = 16, count = 10)
        a.removeRange(0L..0L)
        assertEquals((1L..9L).toList(), a.keysInOrder())
        assertEquals(1, startOf(a))
    }

    @Test
    fun `removeRange only the last key`() {
        val a = filled(capacity = 16, count = 10)
        a.removeRange(9L..9L)
        assertEquals((0L..8L).toList(), a.keysInOrder())
        assertEquals(9, endOf(a))
    }

    @Test
    fun `removeRange prefix nulls the vacated front value slots`() {
        val a = filled(capacity = 16, count = 10)
        a.removeRange(0L..4L)
        val values = valuesArray(a)
        for (i in 0 until 5) assertNull(values[i])
        for (i in 5 until 10) assertEquals("v$i", values[i])
    }

    @Test
    fun `removeRange suffix nulls the vacated back value slots`() {
        val a = filled(capacity = 16, count = 10)
        a.removeRange(5L..9L)
        val values = valuesArray(a)
        for (i in 0 until 5) assertEquals("v$i", values[i])
        for (i in 5 until 10) assertNull(values[i])
    }

    @Test
    fun `prefix and suffix and interior removals release every removed value reference`() {
        val a = MutableLongObjectArrayMap<Any>(16)
        val refs = (0 until 10).map { Any() }
        refs.forEachIndexed { i, v -> a.put(i.toLong(), v) }

        a.removeRange(0L..3L) // prefix: start advances
        a.removeRange(8L..9L) // suffix: end shrinks
        a.removeRange(5L..6L) // interior: tail compacts over the hole

        val live = valuesArray(a).filterNotNull()
        for ((i, r) in refs.withIndex()) {
            if (i == 4 || i == 7) {
                assertTrue(live.any { it === r }, "survivor $i was dropped")
            } else {
                assertTrue(live.none { it === r }, "removed reference $i is still pinned")
            }
        }
    }

    @Test
    fun `removeRange from before the first to a middle key is a prefix trim`() {
        val a = MutableLongObjectArrayMap<String>()
        listOf(10L, 20L, 30L, 40L, 50L).forEach { a.put(it, "v$it") }
        a.removeRange(Long.MIN_VALUE..25L)
        assertEquals(listOf(30L, 40L, 50L), a.keysInOrder())
    }

    @Test
    fun `removeRange from a middle key to after the last is a suffix trim`() {
        val a = MutableLongObjectArrayMap<String>()
        listOf(10L, 20L, 30L, 40L, 50L).forEach { a.put(it, "v$it") }
        a.removeRange(25L..Long.MAX_VALUE)
        assertEquals(listOf(10L, 20L), a.keysInOrder())
    }

    @Test
    fun `removeRange whose bounds fall in the gaps keeps the outer keys`() {
        val a = MutableLongObjectArrayMap<String>()
        listOf(10L, 20L, 30L, 40L, 50L).forEach { a.put(it, "v$it") }
        a.removeRange(15L..45L)
        assertEquals(listOf(10L, 50L), a.keysInOrder())
    }

    @Test
    fun `removeRange Long MIN to MIN and MAX to MAX on present keys`() {
        val a = MutableLongObjectArrayMap<String>()
        a.put(Long.MIN_VALUE, "min"); a.put(0L, "z"); a.put(Long.MAX_VALUE, "max")
        a.removeRange(Long.MIN_VALUE..Long.MIN_VALUE)
        assertEquals(listOf(0L, Long.MAX_VALUE), a.keysInOrder())
        a.removeRange(Long.MAX_VALUE..Long.MAX_VALUE)
        assertEquals(listOf(0L), a.keysInOrder())
    }

    @Test
    fun `removeRange nulls the freed slot of an interior hole`() {
        val a = filled(capacity = 16, count = 10)
        a.removeRange(2L..4L)
        val values = valuesArray(a)
        for (i in 0..1) assertEquals("v$i", values[i])
        for (i in 2..6) assertEquals("v${i + 3}", values[i])
        for (i in 7 until 10) assertNull(values[i])
    }

    @Test
    fun `removeRange then reinsert into the hole keeps order`() {
        val a = filled(capacity = 16, count = 10)
        a.removeRange(3L..6L)
        a.put(4L, "v4b"); a.put(5L, "v5b")
        assertEquals(listOf(0L, 1L, 2L, 4L, 5L, 7L, 8L, 9L), a.keysInOrder())
        assertEquals("v4b", a[4L])
        assertEquals("v5b", a[5L])
    }

    @Test
    fun `removeRange on an advanced start with an interior hole keeps start`() {
        val a = filled(capacity = 16, count = 12)
        a.removeBefore(3L, inclusive = false)
        assertEquals(3, startOf(a))
        a.removeRange(6L..8L)
        assertEquals(3, startOf(a))
        assertEquals(9, endOf(a))
        assertEquals(listOf(3L, 4L, 5L, 9L, 10L, 11L), a.keysInOrder())
    }

    @Test
    fun `removeRange prefix on a large map releases capacity after the start advance`() {
        val a = filled(capacity = 1000, count = 1000)
        a.removeRange(0L..899L)
        assertEquals(100, a.size)
        assertTrue(capacity(a) < 1000)
    }

    @Test
    fun `removeRange interior can release capacity`() {
        val a = filled(capacity = 1000, count = 1000)
        a.removeRange(100L..899L)
        assertEquals(200, a.size)
        assertTrue(capacity(a) < 1000)
    }

    // ---- randomized models -------------------------------------------------------------------

    @Test
    fun `randomized range removals match a reference sorted map`() {
        val a = MutableLongObjectArrayMap<Long>(200)
        val model = sortedMapOf<Long, Long>()
        for (k in 0L until 500L) { a.put(k, k); model[k] = k }

        val rnd = java.util.Random(7)
        repeat(200) {
            val lo = rnd.nextInt(500).toLong()
            val hi = lo + rnd.nextInt(20)
            a.removeRange(lo..hi)
            model.keys.removeIf { it in lo..hi }
        }
        assertEquals(model.keys.toList(), a.keysInOrder())
        model.forEach { (k, v) -> assertEquals(v, a[k]) }
    }

    @Test
    fun `interleaved removeBefore removeAfter and removeRange match a reference map`() {
        val a = MutableLongObjectArrayMap<Long>(64)
        val model = sortedMapOf<Long, Long>()
        for (k in 0L until 300L) { a.put(k, k * 3); model[k] = k * 3 }

        val rnd = java.util.Random(13)
        repeat(300) {
            when (rnd.nextInt(3)) {
                0 -> {
                    val at = rnd.nextInt(320).toLong() - 10
                    val inc = rnd.nextBoolean()
                    a.removeBefore(at, inc)
                    model.keys.removeIf { if (inc) it <= at else it < at }
                }
                1 -> {
                    val at = rnd.nextInt(320).toLong() - 10
                    val inc = rnd.nextBoolean()
                    a.removeAfter(at, inc)
                    model.keys.removeIf { if (inc) it >= at else it > at }
                }
                else -> {
                    val lo = rnd.nextInt(300).toLong()
                    val hi = lo + rnd.nextInt(15)
                    a.removeRange(lo..hi)
                    model.keys.removeIf { it in lo..hi }
                }
            }
        }
        assertEquals(model.keys.toList(), a.keysInOrder())
        assertEquals(model.keys.map { model[it] }, model.keys.map { a[it] })
    }
}
