package fr.rowlaxx.springkutils.array

import java.nio.ByteBuffer

/**
 * Low-level, type-agnostic primitives shared by the entangled-array serializers.
 *
 * An entangled array is a parallel `(level, quantity)` pair of arrays with strictly ascending
 * levels. The on-wire form exploits two regularities of that shape:
 *
 *  - **Packets.** Consecutive levels (each exactly one greater than its predecessor) form a *packet*
 *    whose levels need not be stored at all — only the run length and its starting level, from which
 *    every level is reconstructed as `startLevel + i`. See [computePackets].
 *  - **Variable-width integers.** The packet length, starting level and (for integer quantities) each
 *    value are written with the narrowest of 1/2/4 bytes that holds them ([widthOf], [widthOfRange]),
 *    and the chosen widths are packed two bits each into a single leading *magic* byte
 *    ([packMagic] / [widthAt]).
 *
 * The per-type serializers (e.g. [IntDoubleEntangledArray.serialize]) own the layout — how many
 * fields per packet and how quantities are encoded — and lean on these helpers for the shared
 * mechanics.
 */
object ArrayIOUtils {

    /** A half-open run `[start, endExclusive)` of consecutive levels within a level array. */
    data class Span(val start: Int, val endExclusive: Int)

    /**
     * Splits the window `[start, end)` of [levels] into maximal runs of consecutive levels — a new
     * packet begins wherever `levels[i] != levels[i - 1] + 1`. The returned spans cover the window
     * gaplessly and in order; an empty window yields an empty list. [levels] is assumed ascending.
     */
    fun computePackets(levels: IntArray, start: Int, end: Int): List<Span> {
        require(start <= end) { "start ($start) must not exceed end ($end)" }
        if (start == end) return emptyList()

        val packets = mutableListOf<Span>()
        var packetStart = start

        for (i in start + 1 until end) {
            if (levels[i] != levels[i - 1] + 1) {
                packets.add(Span(packetStart, i))
                packetStart = i
            }
        }
        packets.add(Span(packetStart, end))
        return packets
    }

    /** Narrowest signed width (1, 2 or 4 bytes) that can hold [value]. */
    fun widthOf(value: Int): Int = when (value) {
        in Byte.MIN_VALUE..Byte.MAX_VALUE -> 1
        in Short.MIN_VALUE..Short.MAX_VALUE -> 2
        else -> 4
    }

    /** Narrowest signed width (1, 2 or 4 bytes) that can hold every value in `[min, max]`. */
    fun widthOfRange(min: Int, max: Int): Int = maxOf(widthOf(min), widthOf(max))

    /** Minimum and maximum of the non-empty window `[start, end)` of [values]. */
    fun computeMinMax(values: IntArray, start: Int, end: Int): Pair<Int, Int> {
        require(start < end) { "window [$start, $end) must be non-empty" }

        var min = values[start]
        var max = values[start]
        for (i in start + 1 until end) {
            if (values[i] < min) min = values[i]
            if (values[i] > max) max = values[i]
        }
        return min to max
    }

    /**
     * Packs up to four [widths] (each 1, 2 or 4) into a single magic byte, two bits per width with
     * the first width in the least-significant slot. Read back with [widthAt].
     */
    fun packMagic(vararg widths: Int): Byte {
        require(widths.size <= 4) { "at most 4 widths fit in a magic byte, got ${widths.size}" }

        var magic = 0
        for (i in widths.indices) {
            magic = magic or (encodeWidth(widths[i]) shl (2 * i))
        }
        return magic.toByte()
    }

    /** Reads the width (1, 2 or 4) stored in slot [slot] of a [magic] byte produced by [packMagic]. */
    fun widthAt(magic: Int, slot: Int): Int = decodeWidth((magic shr (2 * slot)) and 0x03)

    /** Maps a byte width (1, 2, 4) to its two-bit code (0, 1, 2). */
    fun encodeWidth(width: Int): Int = when (width) {
        1 -> 0
        2 -> 1
        4 -> 2
        else -> error("invalid width: $width")
    }

    /** Maps a two-bit code (0, 1, 2) back to a byte width (1, 2, 4). */
    fun decodeWidth(encoded: Int): Int = when (encoded) {
        0 -> 1
        1 -> 2
        2 -> 4
        else -> throw IllegalArgumentException("invalid encoded width: $encoded")
    }

    /** Writes [value] to [buffer] using [width] bytes (1, 2 or 4), truncating to that width. */
    fun putInt(buffer: ByteBuffer, value: Int, width: Int) {
        when (width) {
            1 -> buffer.put(value.toByte())
            2 -> buffer.putShort(value.toShort())
            4 -> buffer.putInt(value)
            else -> error("invalid width: $width")
        }
    }

    /** Reads a sign-extended [width]-byte (1, 2 or 4) int from [buffer]. */
    fun readInt(buffer: ByteBuffer, width: Int): Int = when (width) {
        1 -> buffer.get().toInt()
        2 -> buffer.getShort().toInt()
        4 -> buffer.getInt()
        else -> error("invalid width: $width")
    }
}
