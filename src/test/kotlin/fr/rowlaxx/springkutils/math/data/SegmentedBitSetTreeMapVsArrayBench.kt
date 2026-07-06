package fr.rowlaxx.springkutils.math.data

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.TreeMap
import kotlin.system.measureNanoTime

/**
 * Not a correctness test — quantifies the effect of backing [SegmentedBitSet] with a
 * [fr.rowlaxx.springkutils.array.MutableLongLongEntangledArray] instead of a `TreeMap<Long, Long>`.
 *
 * The old design ([LegacySegmentedBitSet] below — a faithful copy of the pre-refactor `TreeMap`
 * algorithms) is measured head-to-head against the current [MutableSegmentedBitSet] on:
 *  - CPU: wall-clock nanos per op for build (addAll), contains, and remove.
 *  - RAM: retained heap for a large number of clustered segments.
 *
 * A `TreeMap<Long, Long>` entry is a red-black `Node` (colour + three references) holding two *boxed*
 * `Long`s; the array holds two primitive `long`s in parallel arrays with no per-entry object. So the
 * new backing is expected to retain far less memory and scan at least as fast.
 *
 * Run manually:
 *   ./gradlew test --tests "*SegmentedBitSetTreeMapVsArrayBench" -i
 */
class SegmentedBitSetTreeMapVsArrayBench {

    /** The pre-refactor `TreeMap<Long, Long>`-backed design, copied verbatim for comparison. */
    private class LegacySegmentedBitSet {
        val content = TreeMap<Long, Long>()

        fun add(number: Long) = addAll(number..number)
        fun remove(number: Long) = removeAll(number..number)

        fun contains(number: Long): Boolean {
            val entry = content.floorEntry(number) ?: return false
            return number <= entry.value
        }

        fun addAll(range: LongRange) {
            if (range.isEmpty()) return
            var newStart = range.first
            var newEnd = range.last

            val floor = if (newStart > Long.MIN_VALUE) content.floorEntry(newStart - 1) else content.floorEntry(newStart)
            if (floor != null && floor.value >= newStart - 1) {
                newStart = minOf(newStart, floor.key)
                newEnd = maxOf(newEnd, floor.value)
                content.remove(floor.key)
            }

            var ceiling = content.ceilingEntry(newStart)
            while (ceiling != null && (newEnd == Long.MAX_VALUE || ceiling.key <= newEnd + 1)) {
                newEnd = maxOf(newEnd, ceiling.value)
                content.remove(ceiling.key)
                ceiling = content.ceilingEntry(newStart)
            }

            content[newStart] = newEnd
        }

        fun removeAll(range: LongRange) {
            if (range.isEmpty()) return
            val start = range.first
            val end = range.last

            val floor = content.floorEntry(start)
            if (floor != null && floor.value >= start) {
                val oldEnd = floor.value
                if (floor.key < start) content[floor.key] = start - 1 else content.remove(floor.key)
                if (oldEnd > end) content[end + 1] = oldEnd
            }

            var ceiling = content.ceilingEntry(start)
            while (ceiling != null && ceiling.key <= end) {
                val oldEnd = ceiling.value
                content.remove(ceiling.key)
                if (oldEnd > end) { content[end + 1] = oldEnd; break }
                ceiling = content.ceilingEntry(start)
            }
        }
    }

    private fun retainedMemory(): Long {
        repeat(3) { System.gc(); Thread.sleep(60) }
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    @Test
    fun `CPU build contains remove old vs new`() {
        val count = 100_000

        // Warm up both implementations so the JIT compiles their hot paths before timing.
        repeat(3) {
            val warmNew = MutableSegmentedBitSet()
            val warmOld = LegacySegmentedBitSet()
            for (i in 0L until 5_000L) {
                warmNew.addAll((i * 10)..(i * 10 + 5))
                warmOld.addAll((i * 10)..(i * 10 + 5))
            }
            for (i in 0L until 5_000L) { warmNew.contains(i * 10 + 3); warmOld.contains(i * 10 + 3) }
            for (i in 0L until 5_000L) { warmNew.remove(i * 10 + 3); warmOld.remove(i * 10 + 3) }
        }

        val newSet = MutableSegmentedBitSet()
        val oldSet = LegacySegmentedBitSet()

        val newAdd = measureNanoTime { for (i in 0L until count) newSet.addAll((i * 10)..(i * 10 + 5)) }
        val oldAdd = measureNanoTime { for (i in 0L until count) oldSet.addAll((i * 10)..(i * 10 + 5)) }

        var sink = 0
        val newContains = measureNanoTime {
            for (i in 0L until count) { if (newSet.contains(i * 10 + 3)) sink++; if (newSet.contains(i * 10 + 7)) sink++ }
        }
        val oldContains = measureNanoTime {
            for (i in 0L until count) { if (oldSet.contains(i * 10 + 3)) sink++; if (oldSet.contains(i * 10 + 7)) sink++ }
        }

        val newRemove = measureNanoTime { for (i in 0L until count) newSet.remove(i * 10 + 3) }
        val oldRemove = measureNanoTime { for (i in 0L until count) oldSet.remove(i * 10 + 3) }

        fun ns(total: Long, ops: Int) = "%.1f".format(total.toDouble() / ops)
        println("=== SegmentedBitSet CPU: TreeMap (old) vs MutableLongLongEntangledArray (new), $count segments ===")
        println("op        | old ns/op | new ns/op")
        println("addAll    | ${ns(oldAdd, count).padStart(9)} | ${ns(newAdd, count)}")
        println("contains  | ${ns(oldContains, count * 2).padStart(9)} | ${ns(newContains, count * 2)}")
        println("remove    | ${ns(oldRemove, count).padStart(9)} | ${ns(newRemove, count)}")
        println("(sink=$sink)")

        // Timing is noisy under full-suite contention; only guard against a gross regression.
        assertTrue(newAdd < oldAdd * 3, "new addAll should not be grossly slower: old=$oldAdd new=$newAdd")
        assertTrue(newContains < oldContains * 3, "new contains should not be grossly slower: old=$oldContains new=$newContains")
    }

    @Test
    fun `RAM footprint old vs new`() {
        val count = 200_000

        // New (array-backed) — measured while the only large object alive is newSet.
        val baseNew = retainedMemory()
        var newSet: MutableSegmentedBitSet? = MutableSegmentedBitSet().apply {
            for (i in 0L until count) addAll((i * 100)..(i * 100 + 50))
        }
        val newMem = retainedMemory() - baseNew
        assertTrue(newSet!!.size() > 0)
        newSet = null

        // Old (TreeMap-backed) — measured after the new set has been released and collected.
        val baseOld = retainedMemory()
        var oldSet: LegacySegmentedBitSet? = LegacySegmentedBitSet().apply {
            for (i in 0L until count) addAll((i * 100)..(i * 100 + 50))
        }
        val oldMem = retainedMemory() - baseOld
        assertTrue(oldSet!!.content.size > 0)
        oldSet = null

        fun mb(bytes: Long) = "%.2f".format(bytes / (1024.0 * 1024.0))
        println("=== SegmentedBitSet RAM: TreeMap (old) vs MutableLongLongEntangledArray (new), $count segments ===")
        println("old (TreeMap<Long,Long>)             = ${mb(oldMem)} MB  (${oldMem / count} B/segment)")
        println("new (MutableLongLongEntangledArray)  = ${mb(newMem)} MB  (${newMem / count} B/segment)")
        println("retained reduction = ${"%.1f".format((1 - newMem.toDouble() / oldMem) * 100)}%")

        // Two primitive longs per segment vs a boxed-Long red-black node: a decisive, stable win.
        assertTrue(newMem < oldMem * 0.6,
            "array backing should retain well under the TreeMap: old=$oldMem new=$newMem")
    }
}
