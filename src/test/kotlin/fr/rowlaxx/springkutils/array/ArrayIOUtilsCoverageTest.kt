package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * Additional coverage for [ArrayIOUtils] that complements [ArrayIOUtilsTest]:
 * boundary deep-dives, exhaustive magic-byte combinations, full ByteBuffer round-trips,
 * the [ArrayIOUtils.Span] data class, an end-to-end serializer-mechanic integration test,
 * and allocation/throughput benchmarks for the primitives.
 */
class ArrayIOUtilsCoverageTest {

    // =============================================================================================
    // computePackets — deeper cases
    // =============================================================================================

    @Test
    fun `computePackets handles a long single consecutive packet`() {
        val levels = IntArray(1000) { it + 50 } // 50..1049, all consecutive
        val packets = ArrayIOUtils.computePackets(levels, 0, 1000)
        assertEquals(listOf(ArrayIOUtils.Span(0, 1000)), packets)
    }

    @Test
    fun `computePackets splits every element when each is a gap`() {
        // step of 2 -> never consecutive -> one span per element
        val levels = intArrayOf(0, 2, 4, 6, 8)
        val packets = ArrayIOUtils.computePackets(levels, 0, 5)
        assertEquals(
            listOf(
                ArrayIOUtils.Span(0, 1),
                ArrayIOUtils.Span(1, 2),
                ArrayIOUtils.Span(2, 3),
                ArrayIOUtils.Span(3, 4),
                ArrayIOUtils.Span(4, 5),
            ),
            packets,
        )
    }

    @Test
    fun `computePackets handles alternating runs and gaps`() {
        // 1,2 (gap to 5) | 5,6,7,8 (gap to 20) | 20,21
        val levels = intArrayOf(1, 2, 5, 6, 7, 8, 20, 21)
        val packets = ArrayIOUtils.computePackets(levels, 0, 8)
        assertEquals(
            listOf(
                ArrayIOUtils.Span(0, 2),
                ArrayIOUtils.Span(2, 6),
                ArrayIOUtils.Span(6, 8),
            ),
            packets,
        )
    }

    @Test
    fun `computePackets treats a single gap at the end as its own span`() {
        val levels = intArrayOf(1, 2, 3, 99)
        assertEquals(
            listOf(ArrayIOUtils.Span(0, 3), ArrayIOUtils.Span(3, 4)),
            ArrayIOUtils.computePackets(levels, 0, 4),
        )
    }

    @Test
    fun `computePackets treats a single gap at the start as its own span`() {
        val levels = intArrayOf(1, 50, 51, 52)
        assertEquals(
            listOf(ArrayIOUtils.Span(0, 1), ArrayIOUtils.Span(1, 4)),
            ArrayIOUtils.computePackets(levels, 0, 4),
        )
    }

    @Test
    fun `computePackets works across the negative-to-positive boundary as one run`() {
        // -2,-1,0,1,2 are all consecutive
        val levels = intArrayOf(-2, -1, 0, 1, 2)
        assertEquals(listOf(ArrayIOUtils.Span(0, 5)), ArrayIOUtils.computePackets(levels, 0, 5))
    }

    @Test
    fun `computePackets with all-negative levels`() {
        // -10,-9 | -5,-4
        val levels = intArrayOf(-10, -9, -5, -4)
        assertEquals(
            listOf(ArrayIOUtils.Span(0, 2), ArrayIOUtils.Span(2, 4)),
            ArrayIOUtils.computePackets(levels, 0, 4),
        )
    }

    @Test
    fun `computePackets on a descending window splits every element`() {
        // descending input violates the ascending precondition but the impl only checks
        // levels[i] == levels[i-1] + 1; descending is never consecutive, so every element splits.
        val levels = intArrayOf(5, 4, 3, 2)
        assertEquals(
            listOf(
                ArrayIOUtils.Span(0, 1),
                ArrayIOUtils.Span(1, 2),
                ArrayIOUtils.Span(2, 3),
                ArrayIOUtils.Span(3, 4),
            ),
            ArrayIOUtils.computePackets(levels, 0, 4),
        )
    }

    @Test
    fun `computePackets returns gapless ordered spans that cover the window exactly`() {
        val levels = intArrayOf(0, 1, 2, 10, 11, 30, 40, 41, 42)
        val start = 1
        val end = 9
        val packets = ArrayIOUtils.computePackets(levels, start, end)

        assertEquals(start, packets.first().start, "first span starts at window start")
        assertEquals(end, packets.last().endExclusive, "last span ends at window end")
        for (i in 1 until packets.size) {
            assertEquals(
                packets[i - 1].endExclusive,
                packets[i].start,
                "spans must be gapless: ${packets[i - 1]} -> ${packets[i]}",
            )
            assertTrue(packets[i].start > packets[i - 1].start, "spans must be in ascending order")
        }
        // Total elements covered equals the window size.
        assertEquals(end - start, packets.sumOf { it.endExclusive - it.start })
    }

    @Test
    fun `computePackets accepts an empty window with start equal to end at array bounds`() {
        assertTrue(ArrayIOUtils.computePackets(intArrayOf(1, 2, 3), 3, 3).isEmpty())
    }

    // =============================================================================================
    // widthOf / widthOfRange — exhaustive boundaries
    // =============================================================================================

    @Test
    fun `widthOf off-by-one around the byte boundary`() {
        assertEquals(1, ArrayIOUtils.widthOf(Byte.MAX_VALUE.toInt()))      // 127
        assertEquals(2, ArrayIOUtils.widthOf(Byte.MAX_VALUE.toInt() + 1))  // 128
        assertEquals(1, ArrayIOUtils.widthOf(Byte.MIN_VALUE.toInt()))      // -128
        assertEquals(2, ArrayIOUtils.widthOf(Byte.MIN_VALUE.toInt() - 1))  // -129
    }

    @Test
    fun `widthOf off-by-one around the short boundary`() {
        assertEquals(2, ArrayIOUtils.widthOf(Short.MAX_VALUE.toInt()))      // 32767
        assertEquals(4, ArrayIOUtils.widthOf(Short.MAX_VALUE.toInt() + 1))  // 32768
        assertEquals(2, ArrayIOUtils.widthOf(Short.MIN_VALUE.toInt()))      // -32768
        assertEquals(4, ArrayIOUtils.widthOf(Short.MIN_VALUE.toInt() - 1))  // -32769
    }

    @Test
    fun `widthOf at the int extremes`() {
        assertEquals(4, ArrayIOUtils.widthOf(Int.MAX_VALUE))
        assertEquals(4, ArrayIOUtils.widthOf(Int.MIN_VALUE))
        assertEquals(1, ArrayIOUtils.widthOf(-1))
        assertEquals(1, ArrayIOUtils.widthOf(1))
    }

    @Test
    fun `widthOfRange spanning negative to positive picks the wider end`() {
        assertEquals(1, ArrayIOUtils.widthOfRange(-128, 127))    // both fit in 1 byte
        assertEquals(2, ArrayIOUtils.widthOfRange(-128, 128))    // max needs 2
        assertEquals(2, ArrayIOUtils.widthOfRange(-129, 127))    // min needs 2
        assertEquals(2, ArrayIOUtils.widthOfRange(-32768, 32767)) // both fit in 2 bytes
        assertEquals(4, ArrayIOUtils.widthOfRange(-32768, 32768)) // max needs 4
        assertEquals(4, ArrayIOUtils.widthOfRange(-32769, 32767)) // min needs 4
    }

    @Test
    fun `widthOfRange is symmetric in argument magnitude`() {
        assertEquals(ArrayIOUtils.widthOfRange(-1000, 5), ArrayIOUtils.widthOfRange(-5, 1000))
        assertEquals(4, ArrayIOUtils.widthOfRange(Int.MIN_VALUE, Int.MAX_VALUE))
    }

    @Test
    fun `widthOfRange of a degenerate single-value range`() {
        assertEquals(1, ArrayIOUtils.widthOfRange(0, 0))
        assertEquals(2, ArrayIOUtils.widthOfRange(300, 300))
        assertEquals(4, ArrayIOUtils.widthOfRange(100_000, 100_000))
    }

    // =============================================================================================
    // computeMinMax — deeper cases
    // =============================================================================================

    @Test
    fun `computeMinMax over all-negative values`() {
        assertEquals(-9 to -1, ArrayIOUtils.computeMinMax(intArrayOf(-3, -9, -1, -5), 0, 4))
    }

    @Test
    fun `computeMinMax over all-equal values`() {
        assertEquals(7 to 7, ArrayIOUtils.computeMinMax(intArrayOf(7, 7, 7, 7), 0, 4))
    }

    @Test
    fun `computeMinMax with min at the end and max at the start`() {
        assertEquals(-5 to 100, ArrayIOUtils.computeMinMax(intArrayOf(100, 3, 4, 50, -5), 0, 5))
    }

    @Test
    fun `computeMinMax with extreme int values`() {
        assertEquals(
            Int.MIN_VALUE to Int.MAX_VALUE,
            ArrayIOUtils.computeMinMax(intArrayOf(0, Int.MAX_VALUE, -1, Int.MIN_VALUE), 0, 4),
        )
    }

    @Test
    fun `computeMinMax over a trailing sub-window`() {
        assertEquals(2 to 4, ArrayIOUtils.computeMinMax(intArrayOf(999, 999, 3, 2, 4), 2, 5))
    }

    @Test
    fun `computeMinMax rejects start greater than end`() {
        assertThrows<IllegalArgumentException> { ArrayIOUtils.computeMinMax(intArrayOf(1, 2, 3), 2, 1) }
    }

    // =============================================================================================
    // packMagic / widthAt — exhaustive combinations
    // =============================================================================================

    @Test
    fun `packMagic and widthAt round-trip all 81 four-slot combinations`() {
        val widths = intArrayOf(1, 2, 4)
        for (a in widths) for (b in widths) for (c in widths) for (d in widths) {
            val magic = ArrayIOUtils.packMagic(a, b, c, d).toInt() and 0xFF
            assertEquals(a, ArrayIOUtils.widthAt(magic, 0), "slot 0 of [$a,$b,$c,$d]")
            assertEquals(b, ArrayIOUtils.widthAt(magic, 1), "slot 1 of [$a,$b,$c,$d]")
            assertEquals(c, ArrayIOUtils.widthAt(magic, 2), "slot 2 of [$a,$b,$c,$d]")
            assertEquals(d, ArrayIOUtils.widthAt(magic, 3), "slot 3 of [$a,$b,$c,$d]")
        }
    }

    @Test
    fun `packMagic round-trips partial slot counts`() {
        // 1 width
        run {
            val magic = ArrayIOUtils.packMagic(2).toInt() and 0xFF
            assertEquals(2, ArrayIOUtils.widthAt(magic, 0))
        }
        // 3 widths
        run {
            val magic = ArrayIOUtils.packMagic(4, 1, 2).toInt() and 0xFF
            assertEquals(4, ArrayIOUtils.widthAt(magic, 0))
            assertEquals(1, ArrayIOUtils.widthAt(magic, 1))
            assertEquals(2, ArrayIOUtils.widthAt(magic, 2))
        }
    }

    @Test
    fun `packMagic leaves unwritten slots at the default width of one`() {
        // Only slot 0 written; slots 1..3 are zero bits -> decode to width 1.
        val magic = ArrayIOUtils.packMagic(4).toInt() and 0xFF
        assertEquals(4, ArrayIOUtils.widthAt(magic, 0))
        assertEquals(1, ArrayIOUtils.widthAt(magic, 1))
        assertEquals(1, ArrayIOUtils.widthAt(magic, 2))
        assertEquals(1, ArrayIOUtils.widthAt(magic, 3))
    }

    @Test
    fun `packMagic with no widths yields a zero magic byte`() {
        assertEquals(0, ArrayIOUtils.packMagic().toInt() and 0xFF)
    }

    @Test
    fun `packMagic rejects an invalid width via encodeWidth`() {
        assertThrows<IllegalStateException> { ArrayIOUtils.packMagic(1, 3) }
    }

    @Test
    fun `widthAt accepts exactly four slots`() {
        val magic = ArrayIOUtils.packMagic(1, 2, 4, 2).toInt() and 0xFF
        assertEquals(2, ArrayIOUtils.widthAt(magic, 3))
    }

    // =============================================================================================
    // encodeWidth / decodeWidth — extra
    // =============================================================================================

    @Test
    fun `encodeWidth maps each valid width to its code`() {
        assertEquals(0, ArrayIOUtils.encodeWidth(1))
        assertEquals(1, ArrayIOUtils.encodeWidth(2))
        assertEquals(2, ArrayIOUtils.encodeWidth(4))
    }

    @Test
    fun `decodeWidth maps each valid code to its width`() {
        assertEquals(1, ArrayIOUtils.decodeWidth(0))
        assertEquals(2, ArrayIOUtils.decodeWidth(1))
        assertEquals(4, ArrayIOUtils.decodeWidth(2))
    }

    @Test
    fun `encodeWidth rejects width zero and other invalid widths`() {
        assertThrows<IllegalStateException> { ArrayIOUtils.encodeWidth(0) }
        assertThrows<IllegalStateException> { ArrayIOUtils.encodeWidth(8) }
        assertThrows<IllegalStateException> { ArrayIOUtils.encodeWidth(-1) }
    }

    @Test
    fun `decodeWidth rejects code three and other invalid codes`() {
        assertThrows<IllegalArgumentException> { ArrayIOUtils.decodeWidth(3) }
        assertThrows<IllegalArgumentException> { ArrayIOUtils.decodeWidth(-1) }
        assertThrows<IllegalArgumentException> { ArrayIOUtils.decodeWidth(100) }
    }

    // =============================================================================================
    // putInt / readInt — full round-trips, truncation, position advancement
    // =============================================================================================

    @Test
    fun `putInt advances buffer position by exactly the width`() {
        for (width in intArrayOf(1, 2, 4)) {
            val buffer = ByteBuffer.allocate(16)
            ArrayIOUtils.putInt(buffer, 5, width)
            assertEquals(width, buffer.position(), "width $width should advance position by $width")
        }
    }

    @Test
    fun `readInt advances buffer position by exactly the width`() {
        for (width in intArrayOf(1, 2, 4)) {
            val buffer = ByteBuffer.allocate(16)
            ArrayIOUtils.putInt(buffer, 5, width)
            buffer.flip()
            ArrayIOUtils.readInt(buffer, width)
            assertEquals(width, buffer.position(), "width $width should advance read position by $width")
        }
    }

    @Test
    fun `putInt then readInt round-trips boundary values at width 1`() {
        for (v in intArrayOf(0, 1, -1, 127, -128)) {
            assertEquals(v, roundTrip(v, 1), "width-1 round trip of $v")
        }
    }

    @Test
    fun `putInt then readInt round-trips boundary values at width 2`() {
        for (v in intArrayOf(0, 1, -1, 128, -129, 32767, -32768)) {
            assertEquals(v, roundTrip(v, 2), "width-2 round trip of $v")
        }
    }

    @Test
    fun `putInt then readInt round-trips boundary values at width 4`() {
        for (v in intArrayOf(0, 1, -1, 32768, -32769, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertEquals(v, roundTrip(v, 4), "width-4 round trip of $v")
        }
    }

    @Test
    fun `putInt truncates out-of-range values at width 1`() {
        // 256 truncated to a byte is 0 once sign-extended; 255 -> -1; -200 -> 56.
        assertEquals(0, roundTrip(256, 1))
        assertEquals(-1, roundTrip(255, 1))
        assertEquals(56, roundTrip(-200, 1))
    }

    @Test
    fun `putInt truncates out-of-range values at width 2`() {
        // 65536 truncated to a short is 0; 65535 -> -1; 0x12345 -> 0x2345 = 9029.
        assertEquals(0, roundTrip(65536, 2))
        assertEquals(-1, roundTrip(65535, 2))
        assertEquals(0x2345, roundTrip(0x12345, 2))
    }

    @Test
    fun `putInt and readInt are consistent across mixed widths written sequentially`() {
        val buffer = ByteBuffer.allocate(64)
        val values = intArrayOf(5, 300, 100_000, -7)
        val widths = intArrayOf(1, 2, 4, 1)
        for (i in values.indices) ArrayIOUtils.putInt(buffer, values[i], widths[i])

        val totalWidth = widths.sum()
        assertEquals(totalWidth, buffer.position())

        buffer.flip()
        for (i in values.indices) {
            // value at i was in range for its width, so it round-trips exactly.
            assertEquals(values[i], ArrayIOUtils.readInt(buffer, widths[i]), "field $i")
        }
        assertEquals(totalWidth, buffer.position(), "read consumed exactly what was written")
    }

    @Test
    fun `putInt rejects invalid width and readInt rejects invalid width`() {
        assertThrows<IllegalStateException> { ArrayIOUtils.putInt(ByteBuffer.allocate(8), 1, 0) }
        assertThrows<IllegalStateException> { ArrayIOUtils.readInt(ByteBuffer.allocate(8), 8) }
    }

    private fun roundTrip(value: Int, width: Int): Int {
        val buffer = ByteBuffer.allocate(width)
        ArrayIOUtils.putInt(buffer, value, width)
        buffer.flip()
        return ArrayIOUtils.readInt(buffer, width)
    }

    // =============================================================================================
    // Span data class
    // =============================================================================================

    @Test
    fun `Span equals and hashCode reflect value semantics`() {
        val a = ArrayIOUtils.Span(2, 5)
        val b = ArrayIOUtils.Span(2, 5)
        val c = ArrayIOUtils.Span(2, 6)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `Span component functions and copy`() {
        val span = ArrayIOUtils.Span(3, 9)
        assertEquals(3, span.component1())
        assertEquals(9, span.component2())
        assertEquals(3, span.start)
        assertEquals(9, span.endExclusive)

        val copied = span.copy(endExclusive = 12)
        assertEquals(3, copied.start)
        assertEquals(12, copied.endExclusive)
        assertEquals(span, span.copy())
    }

    @Test
    fun `Span toString exposes both fields`() {
        val text = ArrayIOUtils.Span(1, 4).toString()
        assertTrue(text.contains("start=1"), text)
        assertTrue(text.contains("endExclusive=4"), text)
    }

    // =============================================================================================
    // Integration — end-to-end serializer mechanic using only these primitives
    // =============================================================================================

    /**
     * Serializes a parallel `(levels, values)` entangled array using only [ArrayIOUtils] and
     * reconstructs it, asserting exact recovery. Per-packet layout written:
     *   magic byte (slots: lengthWidth, startLevelWidth, valueWidth)
     *   length, startLevel, then each value (all variable-width).
     * Levels inside a packet are reconstructed as `startLevel + i`.
     */
    private fun roundTripEntangled(levels: IntArray, values: IntArray) {
        require(levels.size == values.size)
        val n = levels.size
        val buffer = ByteBuffer.allocate(64 + n * 16)

        val packets = ArrayIOUtils.computePackets(levels, 0, n)
        // Write packet count up front with a fixed 4-byte field for simplicity.
        ArrayIOUtils.putInt(buffer, packets.size, 4)

        for (p in packets) {
            val length = p.endExclusive - p.start
            val startLevel = levels[p.start]
            val (vMin, vMax) = ArrayIOUtils.computeMinMax(values, p.start, p.endExclusive)

            val lengthWidth = ArrayIOUtils.widthOf(length)
            val startLevelWidth = ArrayIOUtils.widthOf(startLevel)
            val valueWidth = ArrayIOUtils.widthOfRange(vMin, vMax)

            buffer.put(ArrayIOUtils.packMagic(lengthWidth, startLevelWidth, valueWidth))
            ArrayIOUtils.putInt(buffer, length, lengthWidth)
            ArrayIOUtils.putInt(buffer, startLevel, startLevelWidth)
            for (i in p.start until p.endExclusive) {
                ArrayIOUtils.putInt(buffer, values[i], valueWidth)
            }
        }

        buffer.flip()

        val outLevels = ArrayList<Int>(n)
        val outValues = ArrayList<Int>(n)
        val packetCount = ArrayIOUtils.readInt(buffer, 4)
        assertEquals(packets.size, packetCount)

        repeat(packetCount) {
            val magic = buffer.get().toInt() and 0xFF
            val lengthWidth = ArrayIOUtils.widthAt(magic, 0)
            val startLevelWidth = ArrayIOUtils.widthAt(magic, 1)
            val valueWidth = ArrayIOUtils.widthAt(magic, 2)

            val length = ArrayIOUtils.readInt(buffer, lengthWidth)
            val startLevel = ArrayIOUtils.readInt(buffer, startLevelWidth)
            for (i in 0 until length) {
                outLevels.add(startLevel + i)
                outValues.add(ArrayIOUtils.readInt(buffer, valueWidth))
            }
        }

        assertArrayEquals(levels, outLevels.toIntArray(), "levels mismatch")
        assertArrayEquals(values, outValues.toIntArray(), "values mismatch")
        assertEquals(0, buffer.remaining(), "all written bytes consumed")
    }

    @Test
    fun `integration round-trips a single consecutive packet with small values`() {
        roundTripEntangled(
            levels = intArrayOf(10, 11, 12, 13, 14),
            values = intArrayOf(1, -2, 3, -4, 5),
        )
    }

    @Test
    fun `integration round-trips multiple packets with mixed value widths`() {
        roundTripEntangled(
            levels = intArrayOf(0, 1, 2, 100, 101, 5000, 5001, 5002),
            values = intArrayOf(5, 6, 7, 300, -300, 100_000, -100_000, 1),
        )
    }

    @Test
    fun `integration round-trips packets with large start levels and gaps`() {
        roundTripEntangled(
            levels = intArrayOf(1_000_000, 1_000_001, 2_000_000),
            values = intArrayOf(-1, 0, 1),
        )
    }

    @Test
    fun `integration round-trips many randomized arrays`() {
        val rng = Random(42)
        repeat(50) {
            val n = rng.nextInt(1, 40)
            var level = rng.nextInt(-100, 100)
            val levels = IntArray(n)
            val values = IntArray(n)
            for (i in 0 until n) {
                levels[i] = level
                // advance by 1 (consecutive) or a small gap, keeping strictly ascending
                level += if (rng.nextBoolean()) 1 else rng.nextInt(2, 10)
                values[i] = rng.nextInt(Int.MIN_VALUE, Int.MAX_VALUE)
            }
            roundTripEntangled(levels, values)
        }
    }

    // =============================================================================================
    // Performance / memory — primitives should allocate ~zero per call
    // =============================================================================================

    private fun bytesPerOp(iterations: Int, op: (Int) -> Unit): Double {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val tid = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(tid)
        for (i in 0 until iterations) op(i)
        val bytes = bean.getThreadAllocatedBytes(tid) - before
        return bytes.toDouble() / iterations
    }

    @Test
    fun `cheap primitives allocate near zero per call`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val buffer = ByteBuffer.allocate(64)
        // A single op exercises every cheap primitive once, reusing one buffer.
        val op: (Int) -> Unit = { i ->
            val w = ArrayIOUtils.widthOf(i)
            val wr = ArrayIOUtils.widthOfRange(-i, i)
            val code = ArrayIOUtils.encodeWidth(w)
            ArrayIOUtils.decodeWidth(code)
            val magic = ArrayIOUtils.packMagic(w, wr).toInt() and 0xFF
            ArrayIOUtils.widthAt(magic, 0)
            buffer.clear()
            ArrayIOUtils.putInt(buffer, i, 4)
            buffer.flip()
            ArrayIOUtils.readInt(buffer, 4)
        }

        val warmup = 200_000
        repeat(warmup) { op(it) }

        val iterations = 2_000_000
        val bytes = bytesPerOp(iterations, op)
        println("ArrayIOUtils cheap primitives bytes/op = ${"%.3f".format(bytes)}")

        // packMagic(vararg) may allocate a tiny IntArray for the varargs spread; allow generous slack.
        assertTrue(
            bytes < 64.0,
            "cheap primitives should allocate near zero per call, got $bytes bytes/op",
        )
    }

    @Test
    fun `computeMinMax allocates only the returned Pair per call`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val values = IntArray(10_000) { (it * 31) % 50_000 - 25_000 }
        val op: (Int) -> Unit = { ArrayIOUtils.computeMinMax(values, 0, values.size) }

        repeat(50_000) { op(it) }
        val bytes = bytesPerOp(500_000, op)
        println("ArrayIOUtils.computeMinMax bytes/op = ${"%.2f".format(bytes)} (10k-element window)")

        // One Pair<Int,Int> (two boxed ints + the Pair object) regardless of window size.
        assertTrue(bytes < 128.0, "computeMinMax should allocate only a small Pair, got $bytes bytes/op")
    }

    @Test
    fun `computePackets scales with packet count not array size`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val n = 100_000
        // Fully consecutive -> exactly one Span regardless of size.
        val consecutive = IntArray(n) { it }
        // Every other element a gap -> ~n/2... use step 2 for n spans worst case; keep half-density.
        val sparse = IntArray(n) { it * 2 } // every element is a gap -> n spans

        val consecutiveOp: (Int) -> Unit = { ArrayIOUtils.computePackets(consecutive, 0, n) }
        val sparseOp: (Int) -> Unit = { ArrayIOUtils.computePackets(sparse, 0, n) }

        repeat(50) { consecutiveOp(it); sparseOp(it) }

        val iterations = 200
        val consecutiveBytes = bytesPerOp(iterations, consecutiveOp)
        val sparseBytes = bytesPerOp(iterations, sparseOp)
        println("computePackets bytes/op: consecutive(1 span) = ${"%.0f".format(consecutiveBytes)}, " +
                "sparse($n spans) = ${"%.0f".format(sparseBytes)} over $n elements")

        // The one-packet case allocates dramatically less than the n-packet case: cost tracks
        // packet count, not array length. Generous factor to stay robust under suite contention.
        assertTrue(
            consecutiveBytes < sparseBytes * 0.1,
            "single-packet allocation should be far below n-packet: " +
                    "consecutive=$consecutiveBytes sparse=$sparseBytes",
        )
    }

    @Test
    fun `computePackets throughput on a large array is reasonable`() {
        val n = 1_000_000
        val levels = IntArray(n) { it } // single packet, pure scan cost dominated by allocation of 1 span
        // warmup
        repeat(20) { ArrayIOUtils.computePackets(levels, 0, n) }

        val t0 = System.nanoTime()
        val rounds = 50
        repeat(rounds) { ArrayIOUtils.computePackets(levels, 0, n) }
        val nsPerElement = (System.nanoTime() - t0).toDouble() / (rounds.toLong() * n)
        println("computePackets ns/element (1M array) = ${"%.3f".format(nsPerElement)}")

        // A linear scan; comfortably under 100ns/element even on a contended CI box.
        assertTrue(nsPerElement < 100.0, "computePackets scan too slow: $nsPerElement ns/element")
    }
}
