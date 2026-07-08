package fr.rowlaxx.springkutils.collection.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the post-removal auto-shrink of [MutableLongLongArrayMap]: once a removal drops the live
 * size below 70% of the backing arrays, they are reallocated so the live size fills ~85% of the new,
 * smaller arrays. Mirrors [MutableLongObjectArrayMapShrinkTest]; the all-primitive store pins no
 * references, so the win here is purely releasing the retained `LongArray` capacity.
 */
class MutableLongLongArrayMapShrinkTest {

    // ---- reflection accessors for the private backing state ---------------

    private fun field(a: MutableLongLongArrayMap, name: String): Any? {
        val f = MutableLongLongArrayMap::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(a)
    }

    private fun capacity(a: MutableLongLongArrayMap): Int = (field(a, "keys") as LongArray).size
    private fun startOf(a: MutableLongLongArrayMap): Int = field(a, "start") as Int
    private fun endOf(a: MutableLongLongArrayMap): Int = field(a, "end") as Int

    private fun filled(capacity: Int, count: Int): MutableLongLongArrayMap {
        val a = MutableLongLongArrayMap(capacity)
        for (k in 0 until count) a.put(k.toLong(), k.toLong() * 10)
        return a
    }

    // ---- the feature ------------------------------------------------------

    @Test
    fun `removeIf below 70 percent shrinks the backing arrays to about 85 percent full`() {
        val a = filled(capacity = 1000, count = 1000)
        assertEquals(1000, capacity(a))

        a.removeIf { k, _ -> k >= 500 }

        assertEquals(500, a.size)
        // ceil(500 / 0.85) == 589
        assertEquals(589, capacity(a))
        val usage = a.size.toDouble() / capacity(a)
        assertTrue(usage in 0.70..0.85 + 1e-9, "usage after shrink was $usage")
    }

    @Test
    fun `no shrink while usage stays at or above 70 percent`() {
        val a = filled(capacity = 1000, count = 1000)

        a.removeIf { k, _ -> k >= 800 }

        assertEquals(800, a.size)
        assertEquals(1000, capacity(a))
    }

    @Test
    fun `single-key removals shrink exactly at the 70 percent boundary`() {
        val a = filled(capacity = 100, count = 100)

        for (k in 99 downTo 70) a.remove(k.toLong())
        assertEquals(70, a.size)
        assertEquals(100, capacity(a))

        // 69/100 = 69% → shrink. ceil(69 / 0.85) == 82.
        a.remove(69L)
        assertEquals(69, a.size)
        assertEquals(82, capacity(a))
    }

    @Test
    fun `maps at or below the minimum size never shrink`() {
        // Ten entries in a capacity-64 map stay below the 16-entry floor, so removals never reallocate.
        val a = filled(capacity = 64, count = 10)
        assertEquals(64, capacity(a))

        a.remove(0L); a.remove(9L); a.remove(5L)
        assertEquals(7, a.size)
        assertEquals(64, capacity(a))
    }

    @Test
    fun `shrinking halts at the minimum size and never collapses below it`() {
        val a = filled(capacity = 256, count = 200)

        // Remove down to exactly the 16-entry floor; the arrays shrink along the way.
        for (k in 199 downTo 16) a.remove(k.toLong())
        assertEquals(16, a.size)
        val atMin = capacity(a)
        assertTrue(atMin in 16..255, "capacity at the min size was $atMin")

        // Dropping below the floor must not trigger any further reallocation.
        a.remove(15L)
        assertEquals(15, a.size)
        assertEquals(atMin, capacity(a))

        // Emptying it out entirely keeps the last capacity — it is never collapsed further.
        for (k in 14 downTo 0) a.remove(k.toLong())
        assertTrue(a.isEmpty())
        assertEquals(atMin, capacity(a))
    }

    // ---- correctness is preserved across shrinks --------------------------

    @Test
    fun `contents survive a shrink intact and in order`() {
        val a = filled(capacity = 1000, count = 1000)
        a.removeIf { k, _ -> k >= 400 } // keep 0..399

        assertEquals(400, a.size)
        assertTrue(capacity(a) < 1000)

        val seen = ArrayList<Long>(a.size)
        a.forEach { k, v ->
            seen.add(k)
            assertEquals(k * 10, v)
        }
        assertEquals((0L until 400L).toList(), seen)
        assertEquals(0L, a.getOrDefault(0L, -1L))
        assertEquals(3990L, a.getOrDefault(399L, -1L))
        assertNull(a[400L])
    }

    @Test
    fun `serialization round-trips after a shrink`() {
        val a = filled(capacity = 1000, count = 1000)
        a.removeIf { k, _ -> k >= 300 } // keep 0..299, forcing a shrink
        assertTrue(capacity(a) < 1000)

        val restored = MutableLongLongArrayMap.deserialize(a.serialize())
        assertEquals(a, restored)
        assertEquals(a.size, restored.size)
        restored.forEach { k, v -> assertEquals(k * 10, v) }
    }

    @Test
    fun `interleaved removes match a reference map throughout`() {
        val a = MutableLongLongArrayMap(2000)
        val reference = HashMap<Long, Long>()
        for (k in 0 until 2000L) {
            a.put(k, k * 10)
            reference[k] = k * 10
        }

        for (k in 0 until 2000L) {
            if (k % 3 == 0L || k % 7 == 0L) {
                a.remove(k)
                reference.remove(k)
                assertTrue(capacity(a) >= a.size)
                assertTrue(endOf(a) - startOf(a) == a.size)
            }
        }
        assertEquals(reference.size, a.size)
        for ((k, v) in reference) assertEquals(v, a.getOrDefault(k, -1L))
        assertTrue(capacity(a) < 2000, "capacity ${capacity(a)} was not released")
    }
}
