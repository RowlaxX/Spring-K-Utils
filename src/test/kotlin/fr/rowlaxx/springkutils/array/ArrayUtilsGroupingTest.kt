package fr.rowlaxx.springkutils.array

import fr.rowlaxx.springkutils.array.ArrayUtils.GroupedBy
import fr.rowlaxx.springkutils.array.ArrayUtils.unsafeGroupBy
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for [ArrayUtils.unsafeGroupBy] and its [GroupedBy] result (which also exercises the private
 * `RangeViewList`, `denseGroup`, and `sparseGroup` through their only public entry point).
 *
 * The grouping uses thread-local scratch, so every result is only valid until the next `unsafeGroupBy`
 * on the same thread. These tests consume each result before regrouping, exactly as production must.
 */
class ArrayUtilsGroupingTest {

    private data class E(val key: Long, val id: Int)

    private fun e(key: Long, id: Int) = E(key, id)

    /** Materialises a [GroupedBy] into an ordered list of `(key, ids)` so assertions don't view scratch. */
    private fun <T> GroupedBy<T>.snapshot(idOf: (T) -> Int): List<Pair<Long, List<Int>>> {
        val out = ArrayList<Pair<Long, List<Int>>>()
        forEach { key, list -> out.add(key to list.map(idOf)) }
        return out
    }

    // -- degenerate sizes ---------------------------------------------------------------------------

    @Test
    fun `empty input yields an empty grouping`() {
        val g = emptyList<E>().unsafeGroupBy { it.key }
        assertEquals(0, g.count)
        assertTrue(g.first().isEmpty())
        assertTrue(g.last().isEmpty())
        assertFalse(g.contains(0L))
        assertTrue(g[0L].isEmpty())
        var visited = 0
        g.forEach { _, _ -> visited++ }
        g.forEachKey { visited++ }
        assertEquals(0, visited)
        assertDoesNotThrow { g.close() }
    }

    @Test
    fun `single element grouping`() {
        val g = listOf(e(42L, 1)).unsafeGroupBy { it.key }
        assertEquals(1, g.count)
        assertTrue(g.contains(42L))
        assertEquals(listOf(1), g[42L].map { it.id })
        assertEquals(listOf(1), g.first().map { it.id })
        assertEquals(listOf(1), g.last().map { it.id })
    }

    @Test
    fun `all elements share one key (min equals max)`() {
        val items = (0 until 5).map { e(7L, it) }
        val g = items.unsafeGroupBy { it.key }
        assertEquals(1, g.count)
        assertEquals(listOf(7L to listOf(0, 1, 2, 3, 4)), g.snapshot { it.id })
    }

    // -- dense path (span <= n) ---------------------------------------------------------------------

    @Test
    fun `dense path groups by contiguous keys`() {
        // keys in {0,1,2}, n=6, span=3 <= 6 -> dense (counting sort)
        val items = listOf(e(2, 0), e(0, 1), e(1, 2), e(0, 3), e(2, 4), e(1, 5))
        val g = items.unsafeGroupBy { it.key }
        assertEquals(3, g.count)
        assertEquals(
            listOf(0L to listOf(1, 3), 1L to listOf(2, 5), 2L to listOf(0, 4)),
            g.snapshot { it.id },
        )
    }

    @Test
    fun `dense path is stable within a group`() {
        val items = listOf(e(1, 10), e(1, 11), e(0, 12), e(1, 13))
        val g = items.unsafeGroupBy { it.key }
        assertEquals(listOf(12), g[0L].map { it.id }) // key 0 -> [12]
        assertEquals(listOf(10, 11, 13), g[1L].map { it.id }) // input order preserved
    }

    @Test
    fun `dense path keys come out ascending and deduplicated`() {
        val items = listOf(e(3, 0), e(1, 1), e(3, 2), e(2, 3), e(1, 4))
        val g = items.unsafeGroupBy { it.key }
        val keys = ArrayList<Long>()
        g.forEachKey { keys.add(it) }
        assertEquals(listOf(1L, 2L, 3L), keys)
    }

    @Test
    fun `dense path with span exactly equal to n`() {
        // span == n boundary still takes the dense branch (span <= n)
        val items = listOf(e(0, 0), e(1, 1), e(2, 2)) // n=3, span=3
        val g = items.unsafeGroupBy { it.key }
        assertEquals(3, g.count)
        assertEquals(listOf(0L, 1L, 2L), buildList { g.forEachKey { add(it) } })
    }

    @Test
    fun `dense path with negative keys`() {
        val items = listOf(e(-2, 0), e(-1, 1), e(-2, 2), e(0, 3))
        val g = items.unsafeGroupBy { it.key }
        assertEquals(
            listOf(-2L to listOf(0, 2), -1L to listOf(1), 0L to listOf(3)),
            g.snapshot { it.id },
        )
    }

    // -- sparse path (span > n) ---------------------------------------------------------------------

    @Test
    fun `sparse path groups widely separated keys`() {
        // keys {5, 1000, 7}, n=3, span=996 > 3 -> sparse (comparison sort)
        val items = listOf(e(1000, 0), e(5, 1), e(7, 2), e(1000, 3), e(5, 4))
        val g = items.unsafeGroupBy { it.key }
        assertEquals(3, g.count)
        assertEquals(
            listOf(5L to listOf(1, 4), 7L to listOf(2), 1000L to listOf(0, 3)),
            g.snapshot { it.id },
        )
    }

    @Test
    fun `sparse path is stable within a group`() {
        val items = listOf(e(1_000_000, 0), e(0, 1), e(1_000_000, 2), e(0, 3))
        val g = items.unsafeGroupBy { it.key }
        assertEquals(listOf(1, 3), g[0L].map { it.id })
        assertEquals(listOf(0, 2), g[1_000_000L].map { it.id })
    }

    @Test
    fun `sparse path keys ascending and deduplicated`() {
        val items = listOf(e(100, 0), e(5, 1), e(100, 2), e(50, 3))
        val g = items.unsafeGroupBy { it.key }
        assertEquals(listOf(5L, 50L, 100L), buildList { g.forEachKey { add(it) } })
    }

    @Test
    fun `sparse path with span just over n`() {
        // n=2, span=3 (> 2) -> sparse
        val items = listOf(e(0, 0), e(2, 1))
        val g = items.unsafeGroupBy { it.key }
        assertEquals(2, g.count)
        assertEquals(listOf(0L to listOf(0), 2L to listOf(1)), g.snapshot { it.id })
    }

    @Test
    fun `dense and sparse produce the same logical grouping`() {
        val dense = listOf(e(0, 0), e(1, 1), e(2, 2), e(1, 3))      // span 3 <= n 4 -> dense
        val sparse = listOf(e(0, 0), e(1000, 1), e(2000, 2), e(1000, 3))
        val gDense = dense.unsafeGroupBy { it.key }.snapshot { it.id }
        val gSparse = sparse.unsafeGroupBy { it.key }.snapshot { it.id }
        // same shape: 3 groups, middle key holds ids [1,3]
        assertEquals(listOf(listOf(0), listOf(1, 3), listOf(2)), gDense.map { it.second })
        assertEquals(listOf(listOf(0), listOf(1, 3), listOf(2)), gSparse.map { it.second })
    }

    // -- GroupedBy accessors ------------------------------------------------------------------------

    @Test
    fun `first and last return the lowest and highest key groups`() {
        val items = listOf(e(3, 30), e(1, 10), e(2, 20), e(3, 31), e(1, 11))
        val g = items.unsafeGroupBy { it.key }
        assertEquals(listOf(10, 11), g.first().map { it.id })
        assertEquals(listOf(30, 31), g.last().map { it.id })
    }

    @Test
    fun `first equals last when there is a single key`() {
        val g = listOf(e(9, 1), e(9, 2)).unsafeGroupBy { it.key }
        assertEquals(g.first().map { it.id }, g.last().map { it.id })
    }

    @Test
    fun `contains reports membership`() {
        val g = listOf(e(1, 0), e(3, 1)).unsafeGroupBy { it.key }
        assertTrue(g.contains(1L))
        assertTrue(g.contains(3L))
        assertFalse(g.contains(2L))
        assertFalse(g.contains(0L))
        assertFalse(g.contains(Long.MAX_VALUE))
    }

    @Test
    fun `get returns the group for a present key`() {
        val g = listOf(e(5, 0), e(5, 1), e(8, 2)).unsafeGroupBy { it.key }
        assertEquals(listOf(0, 1), g[5L].map { it.id })
        assertEquals(listOf(2), g[8L].map { it.id })
    }

    @Test
    fun `get returns empty for an absent key`() {
        val g = listOf(e(5, 0)).unsafeGroupBy { it.key }
        assertTrue(g[6L].isEmpty())
        assertTrue(g[-1L].isEmpty())
    }

    @Test
    fun `forEach visits every key with its group in ascending order`() {
        val items = listOf(e(2, 20), e(0, 0), e(1, 10), e(0, 1))
        val g = items.unsafeGroupBy { it.key }
        val seen = LinkedHashMap<Long, List<Int>>()
        g.forEach { key, list -> seen[key] = list.map { it.id } }
        assertEquals(listOf(0L, 1L, 2L), seen.keys.toList())
        assertEquals(listOf(0, 1), seen[0L])
    }

    @Test
    fun `forEachKey visits every distinct key once ascending`() {
        val items = listOf(e(5, 0), e(5, 1), e(2, 2), e(9, 3))
        val keys = ArrayList<Long>()
        items.unsafeGroupBy { it.key }.forEachKey { keys.add(it) }
        assertEquals(listOf(2L, 5L, 9L), keys)
    }

    @Test
    fun `count equals the number of distinct keys`() {
        assertEquals(3, listOf(e(1, 0), e(1, 1), e(2, 2), e(3, 3)).unsafeGroupBy { it.key }.count)
        assertEquals(1, listOf(e(1, 0), e(1, 1)).unsafeGroupBy { it.key }.count)
        assertEquals(0, emptyList<E>().unsafeGroupBy { it.key }.count)
    }

    // -- RangeViewList behaviour (via GroupedBy results) -------------------------------------------

    @Test
    fun `group list reports the right size and indexed elements`() {
        val g = listOf(e(1, 7), e(1, 8), e(1, 9)).unsafeGroupBy { it.key }
        val list = g[1L]
        assertEquals(3, list.size)
        assertEquals(7, list[0].id)
        assertEquals(8, list[1].id)
        assertEquals(9, list[2].id)
    }

    @Test
    fun `group list index out of bounds throws`() {
        val g = listOf(e(1, 7), e(1, 8)).unsafeGroupBy { it.key }
        val list = g[1L]
        assertThrows(IndexOutOfBoundsException::class.java) { list[2] }
        assertThrows(IndexOutOfBoundsException::class.java) { list[-1] }
    }

    // -- the "unsafe" contract & clear -------------------------------------------------------------

    @Test
    fun `a second grouping on the same thread reuses scratch and is correct`() {
        val first = listOf(e(1, 0), e(2, 1)).unsafeGroupBy { it.key }
        assertEquals(2, first.count) // consumed before regrouping
        val second = listOf(e(9, 5), e(9, 6), e(3, 7)).unsafeGroupBy { it.key }
        assertEquals(listOf(3L to listOf(7), 9L to listOf(5, 6)), second.snapshot { it.id })
    }

    @Test
    fun `clear nulls the grouped values so they are not pinned`() {
        val g = listOf(e(1, 0), e(1, 1)).unsafeGroupBy { it.key }
        val view = g.first()
        g.close()
        // The backing scratch slot was nulled; the view now reads null at index 0.
        @Suppress("UNCHECKED_CAST")
        assertNull((view as List<Any?>)[0])
    }

    @Test
    fun `clear on an empty grouping is a no-op`() {
        assertDoesNotThrow { emptyList<E>().unsafeGroupBy { it.key }.close() }
    }

    @Test
    fun `key function derives the grouping key`() {
        // group by a derived bucket (key / 10) rather than the raw value
        val items = listOf(e(11, 0), e(13, 1), e(25, 2), e(29, 3))
        val g = items.unsafeGroupBy { it.key / 10 }
        assertEquals(
            listOf(1L to listOf(0, 1), 2L to listOf(2, 3)),
            g.snapshot { it.id },
        )
    }

    @Test
    fun `large dense input groups every element exactly once`() {
        val items = (0 until 1000).map { e((it % 50).toLong(), it) }
        val g = items.unsafeGroupBy { it.key }
        assertEquals(50, g.count)
        var total = 0
        g.forEach { _, list -> total += list.size }
        assertEquals(1000, total)
    }
}
