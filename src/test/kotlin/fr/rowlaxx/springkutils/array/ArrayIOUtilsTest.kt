package fr.rowlaxx.springkutils.array

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer

/** Unit tests for every public function of [ArrayIOUtils]. */
class ArrayIOUtilsTest {

    // ---------------------------------------------------------------------------------------------
    // computePackets
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `computePackets of an empty window is empty`() {
        assertTrue(ArrayIOUtils.computePackets(intArrayOf(), 0, 0).isEmpty())
        assertTrue(ArrayIOUtils.computePackets(intArrayOf(1, 2, 3), 1, 1).isEmpty())
    }

    @Test
    fun `computePackets of a single element is one span`() {
        assertEquals(listOf(ArrayIOUtils.Span(0, 1)), ArrayIOUtils.computePackets(intArrayOf(7), 0, 1))
    }

    @Test
    fun `computePackets of fully consecutive levels is one span`() {
        val levels = intArrayOf(4, 5, 6, 7, 8)
        assertEquals(listOf(ArrayIOUtils.Span(0, 5)), ArrayIOUtils.computePackets(levels, 0, 5))
    }

    @Test
    fun `computePackets splits at every gap`() {
        // gaps after index 1 (3 -> 10) and index 3 (11 -> 20)
        val levels = intArrayOf(2, 3, 10, 11, 20)
        assertEquals(
            listOf(ArrayIOUtils.Span(0, 2), ArrayIOUtils.Span(2, 4), ArrayIOUtils.Span(4, 5)),
            ArrayIOUtils.computePackets(levels, 0, 5),
        )
    }

    @Test
    fun `computePackets honors the start and end window`() {
        // outside-window neighbours are irrelevant; only [1, 4) is split
        val levels = intArrayOf(100, 5, 6, 9, 200)
        assertEquals(
            listOf(ArrayIOUtils.Span(1, 3), ArrayIOUtils.Span(3, 4)),
            ArrayIOUtils.computePackets(levels, 1, 4),
        )
    }

    @Test
    fun `computePackets rejects start greater than end`() {
        assertThrows<IllegalArgumentException> { ArrayIOUtils.computePackets(intArrayOf(1, 2), 2, 1) }
    }

    // ---------------------------------------------------------------------------------------------
    // widthOf / widthOfRange
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `widthOf picks the narrowest signed width`() {
        assertEquals(1, ArrayIOUtils.widthOf(0))
        assertEquals(1, ArrayIOUtils.widthOf(127))
        assertEquals(1, ArrayIOUtils.widthOf(-128))
        assertEquals(2, ArrayIOUtils.widthOf(128))
        assertEquals(2, ArrayIOUtils.widthOf(-129))
        assertEquals(2, ArrayIOUtils.widthOf(32767))
        assertEquals(2, ArrayIOUtils.widthOf(-32768))
        assertEquals(4, ArrayIOUtils.widthOf(32768))
        assertEquals(4, ArrayIOUtils.widthOf(-32769))
        assertEquals(4, ArrayIOUtils.widthOf(Int.MAX_VALUE))
        assertEquals(4, ArrayIOUtils.widthOf(Int.MIN_VALUE))
    }

    @Test
    fun `widthOfRange takes the wider of the two ends`() {
        assertEquals(1, ArrayIOUtils.widthOfRange(-1, 1))
        assertEquals(2, ArrayIOUtils.widthOfRange(-200, 100))   // -200 needs 2 bytes
        assertEquals(2, ArrayIOUtils.widthOfRange(-1, 1000))    // 1000 needs 2 bytes
        assertEquals(4, ArrayIOUtils.widthOfRange(0, 100_000))
        assertEquals(4, ArrayIOUtils.widthOfRange(-100_000, 0))
    }

    // ---------------------------------------------------------------------------------------------
    // computeMinMax
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `computeMinMax over the whole array`() {
        assertEquals(-3 to 9, ArrayIOUtils.computeMinMax(intArrayOf(5, -3, 9, 0, 4), 0, 5))
    }

    @Test
    fun `computeMinMax over a sub-window ignores outside values`() {
        // values 1000 and -1000 sit outside [1, 4)
        assertEquals(2 to 8, ArrayIOUtils.computeMinMax(intArrayOf(1000, 8, 2, 5, -1000), 1, 4))
    }

    @Test
    fun `computeMinMax of a single element is that element`() {
        assertEquals(42 to 42, ArrayIOUtils.computeMinMax(intArrayOf(42), 0, 1))
    }

    @Test
    fun `computeMinMax rejects an empty window`() {
        assertThrows<IllegalArgumentException> { ArrayIOUtils.computeMinMax(intArrayOf(1, 2), 1, 1) }
    }

    // ---------------------------------------------------------------------------------------------
    // encodeWidth / decodeWidth
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `encodeWidth and decodeWidth round-trip`() {
        for (width in intArrayOf(1, 2, 4)) {
            assertEquals(width, ArrayIOUtils.decodeWidth(ArrayIOUtils.encodeWidth(width)))
        }
        assertEquals(0, ArrayIOUtils.encodeWidth(1))
        assertEquals(1, ArrayIOUtils.encodeWidth(2))
        assertEquals(2, ArrayIOUtils.encodeWidth(4))
    }

    @Test
    fun `encodeWidth rejects an invalid width`() {
        assertThrows<IllegalStateException> { ArrayIOUtils.encodeWidth(3) }
    }

    @Test
    fun `decodeWidth rejects an invalid code`() {
        assertThrows<IllegalArgumentException> { ArrayIOUtils.decodeWidth(3) }
    }

    // ---------------------------------------------------------------------------------------------
    // packMagic / widthAt
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `packMagic and widthAt round-trip every slot`() {
        val widths = intArrayOf(4, 1, 2, 4)
        val magic = ArrayIOUtils.packMagic(*widths).toInt() and 0xFF
        for (slot in widths.indices) {
            assertEquals(widths[slot], ArrayIOUtils.widthAt(magic, slot))
        }
    }

    @Test
    fun `packMagic places the first width in the least-significant slot`() {
        // size=1 -> code 0, start=2 -> code 1; magic = 0 | (1 shl 2) = 4
        val magic = ArrayIOUtils.packMagic(1, 2).toInt() and 0xFF
        assertEquals(0b0100, magic)
        assertEquals(1, ArrayIOUtils.widthAt(magic, 0))
        assertEquals(2, ArrayIOUtils.widthAt(magic, 1))
    }

    @Test
    fun `packMagic rejects more than four widths`() {
        assertThrows<IllegalArgumentException> { ArrayIOUtils.packMagic(1, 1, 1, 1, 1) }
    }

    // ---------------------------------------------------------------------------------------------
    // putInt / readInt
    // ---------------------------------------------------------------------------------------------

    private fun roundTripInt(value: Int, width: Int): Int {
        val buffer = ByteBuffer.allocate(width)
        ArrayIOUtils.putInt(buffer, value, width)
        assertEquals(width, buffer.position(), "putInt should write exactly $width byte(s)")
        buffer.flip()
        return ArrayIOUtils.readInt(buffer, width)
    }

    @Test
    fun `putInt then readInt round-trips in-range values`() {
        assertEquals(127, roundTripInt(127, 1))
        assertEquals(-128, roundTripInt(-128, 1))
        assertEquals(32767, roundTripInt(32767, 2))
        assertEquals(-32768, roundTripInt(-32768, 2))
        assertEquals(Int.MAX_VALUE, roundTripInt(Int.MAX_VALUE, 4))
        assertEquals(Int.MIN_VALUE, roundTripInt(Int.MIN_VALUE, 4))
        assertEquals(0, roundTripInt(0, 1))
    }

    @Test
    fun `putInt truncates and readInt sign-extends to the given width`() {
        // 200 as a byte is 0xC8 = -56 once sign-extended back.
        assertEquals(-56, roundTripInt(200, 1))
        // 0xFFFF as a short is -1 once sign-extended back.
        assertEquals(-1, roundTripInt(0xFFFF, 2))
    }

    @Test
    fun `putInt rejects an invalid width`() {
        assertThrows<IllegalStateException> { ArrayIOUtils.putInt(ByteBuffer.allocate(8), 1, 3) }
    }

    @Test
    fun `readInt rejects an invalid width`() {
        assertThrows<IllegalStateException> { ArrayIOUtils.readInt(ByteBuffer.allocate(8), 3) }
    }
}
