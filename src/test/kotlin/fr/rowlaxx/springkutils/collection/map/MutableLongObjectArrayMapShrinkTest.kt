package fr.rowlaxx.springkutils.collection.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the post-removal auto-shrink of [MutableLongObjectArrayMap]: once a removal drops the live
 * size below 50% of the backing arrays, they are reallocated so the live size fills 66.7% of the new,
 * smaller arrays. The 50%/66.7% marks sit on opposite sides of the 66.7% load a freshly grown (×1.5)
 * map lands on, so a single add/remove after any resize can never immediately undo it. This still
 * releases the capacity retained after a transient size spike (and, for the object store, drops the
 * references the freed slots used to pin).
 */
class MutableLongObjectArrayMapShrinkTest {

    // ---- reflection accessors for the private backing state ---------------

    private fun field(a: MutableLongObjectArrayMap<*>, name: String): Any? {
        val f = MutableLongObjectArrayMap::class.java.getDeclaredField(name).apply { isAccessible = true }
        return f.get(a)
    }

    private fun capacity(a: MutableLongObjectArrayMap<*>): Int = (field(a, "keys") as LongArray).size
    private fun valuesArray(a: MutableLongObjectArrayMap<*>): Array<*> = field(a, "values") as Array<*>
    private fun startOf(a: MutableLongObjectArrayMap<*>): Int = field(a, "start") as Int
    private fun endOf(a: MutableLongObjectArrayMap<*>): Int = field(a, "end") as Int

    private fun filled(capacity: Int, count: Int): MutableLongObjectArrayMap<String> {
        val a = MutableLongObjectArrayMap<String>(capacity)
        for (k in 0 until count) a.put(k.toLong(), "v$k")
        return a
    }

    // ---- the feature ------------------------------------------------------

    @Test
    fun `removeIf below 50 percent shrinks the backing arrays to about 66_7 percent full`() {
        val a = filled(capacity = 1000, count = 1000)
        assertEquals(1000, capacity(a))

        // Drop to 400 live entries (40% full) in a single removal → one shrink evaluation.
        a.removeIf { k, _ -> k >= 400 }

        assertEquals(400, a.size)
        // 1.5 * 400 == 600 (66.7% full).
        assertEquals(600, capacity(a))
        val usage = a.size.toDouble() / capacity(a)
        assertTrue(usage in 0.50..0.6667 + 1e-4, "usage after shrink was $usage")
    }

    @Test
    fun `no shrink while usage stays at or above 50 percent`() {
        val a = filled(capacity = 1000, count = 1000)

        // Keep 600 of 1000 → 60% full, above the low-water mark.
        a.removeIf { k, _ -> k >= 600 }

        assertEquals(600, a.size)
        assertEquals(1000, capacity(a))
    }

    @Test
    fun `single-key removals shrink exactly at the 50 percent boundary`() {
        val a = filled(capacity = 100, count = 100)

        // Remove down to 50 live entries: still exactly 50%, so no shrink yet.
        for (k in 99 downTo 50) a.remove(k.toLong())
        assertEquals(50, a.size)
        assertEquals(100, capacity(a))

        // The next removal drops to 49/100 → below 50% → shrink. 1.5 * 49 == 73.
        a.remove(49L)
        assertEquals(49, a.size)
        assertEquals(73, capacity(a))
    }

    @Test
    fun `arrays already at or below the minimum capacity never shrink`() {
        // A capacity-16 map is already at the floor, so removals never reallocate it.
        val a = filled(capacity = 16, count = 16)
        assertEquals(16, capacity(a))

        a.remove(0L); a.remove(15L); a.remove(5L)
        assertEquals(13, a.size)
        assertEquals(16, capacity(a))
    }

    @Test
    fun `a map that collapses to a handful of entries releases its peak capacity`() {
        // The case that matters in production: a long-lived map spikes, then sits at a few entries.
        // The floor is on capacity, so a small live size does not exempt it from shrinking.
        val a = filled(capacity = 512, count = 400)
        assertEquals(512, capacity(a))

        a.removeIf { k, _ -> k >= 8 }
        assertEquals(8, a.size)
        // 1.5 * 8 == 12, raised to the 16-element floor.
        assertEquals(16, capacity(a))
    }

    @Test
    fun `shrinking halts at the minimum capacity and never collapses below it`() {
        val a = filled(capacity = 256, count = 200)

        // Drain it entirely; the arrays shrink along the way but stop at the floor.
        for (k in 199 downTo 0) a.remove(k.toLong())
        assertTrue(a.isEmpty())
        assertEquals(16, capacity(a))
    }

    // ---- correctness is preserved across shrinks --------------------------

    @Test
    fun `contents survive a shrink intact and in order`() {
        val a = filled(capacity = 1000, count = 1000)
        a.removeIf { k, _ -> k >= 200 } // keep 0..199, 40% full → forces a shrink

        assertEquals(200, a.size)
        assertTrue(capacity(a) < 1000)

        val seen = ArrayList<Long>(a.size)
        a.forEach { k, v ->
            seen.add(k)
            assertEquals("v$k", v)
        }
        assertEquals((0L until 200L).toList(), seen)
        assertEquals("v0", a[0L])
        assertEquals("v199", a[199L])
        assertNull(a[200L])
    }

    @Test
    fun `interleaved removes match a reference map throughout`() {
        val a = MutableLongObjectArrayMap<String>(2000)
        val reference = HashMap<Long, String>()
        for (k in 0 until 2000L) {
            a.put(k, "v$k")
            reference[k] = "v$k"
        }

        // Thin out heavily, keeping only every tenth key — crossing several shrink points.
        var removed = 0
        for (k in 0 until 2000L) {
            if (k % 10 != 0L) {
                a.remove(k)
                reference.remove(k)
                removed++
                // Backing arrays must never grow on removal and always hold the live window.
                assertTrue(capacity(a) >= a.size)
                assertTrue(endOf(a) - startOf(a) == a.size)
            }
        }
        assertTrue(removed > 0)
        assertEquals(reference.size, a.size)
        for ((k, v) in reference) assertEquals(v, a[k])
        // The peak capacity of 2000 must not be pinned after removing a large fraction.
        assertTrue(capacity(a) < 2000, "capacity ${capacity(a)} was not released")
    }

    // ---- object store: freed slots must not pin references ----------------

    @Test
    fun `shrink drops the references held by freed value slots`() {
        val a = filled(capacity = 512, count = 400)
        a.removeIf { k, _ -> k >= 100 } // keep 100 → forces a shrink

        val values = valuesArray(a)
        // The reallocated array is sized to the new capacity, not the old peak of 512.
        assertEquals(capacity(a), values.size)
        assertTrue(values.size < 512)
        // Every slot outside the live [start, end) window holds null — nothing is pinned.
        for (i in values.indices) {
            if (i < startOf(a) || i >= endOf(a)) {
                assertNull(values[i], "slot $i still pinned a reference after shrink")
            }
        }
    }
}
