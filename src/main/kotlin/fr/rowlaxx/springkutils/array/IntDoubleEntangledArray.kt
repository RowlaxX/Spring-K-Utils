package fr.rowlaxx.springkutils.array

import fr.rowlaxx.springkutils.array.ArrayUtils.sortedDirection
import fr.rowlaxx.springkutils.array.IntDoubleEntangledArray.Companion.EMPTY
import fr.rowlaxx.springkutils.array.IntDoubleEntangledArray.Companion.cumulativeSet
import fr.rowlaxx.springkutils.array.IntDoubleEntangledArray.Companion.deserialize
import fr.rowlaxx.springkutils.array.IntDoubleEntangledArray.Companion.weightedSum
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class IntDoubleEntangledArray {
    private val first: IntArray
    private val second: DoubleArray
    private val start: Int
    private val end: Int

    val size get() = end - start
    val firstLevel get() = if (size == 0) throw IndexOutOfBoundsException() else first[start]
    val lastLevel get() = if (size == 0) throw IndexOutOfBoundsException() else first[end - 1]

    /**
     * The lowest level carrying a non-zero quantity, scanning up from the start of the window, or
     * `null` when the array is empty or every entry is zero. For a canonical array (built without
     * zeros) this is simply [firstLevel].
     */
    fun firstNonZeroLevel(): Int? {
        for (i in start until end) {
            if (second[i] != 0.0) return first[i]
        }
        return null
    }

    /**
     * The highest level carrying a non-zero quantity, scanning down from the end of the window, or
     * `null` when the array is empty or every entry is zero. For a canonical array (built without
     * zeros) this is simply [lastLevel].
     */
    fun lastNonZeroLevel(): Int? {
        for (i in end - 1 downTo start) {
            if (second[i] != 0.0) return first[i]
        }
        return null
    }

    /**
     * Materializes the `[start, end)` window as a list of `[level, value]` rows, one [List] of
     * [Number] per entry — each row is `listOf(first, second)`, the level followed by its quantity.
     * By default ([descending] = `false`) rows are in ascending level order; pass [descending] = `true`
     * to emit them from highest level to lowest. Zero entries, if present, are included; an empty array
     * yields an empty list. The returned lists are freshly allocated and share no state with the
     * backing arrays.
     */
    fun toLevelValueList(descending: Boolean = false): List<List<Number>> {
        val result = ArrayList<List<Number>>(size)
        if (descending) {
            for (i in end - 1 downTo start) {
                result.add(listOf(first[i], second[i]))
            }
        } else {
            for (i in start until end) {
                result.add(listOf(first[i], second[i]))
            }
        }
        return result
    }

    operator fun get(level: Int): Double {
        val idx = lowerBound(level)
        return if (idx < end && first[idx] == level) second[idx] else 0.0
    }

    private constructor(first: IntArray, second: DoubleArray) {
        this.first = first
        this.second = second
        this.start = 0
        this.end = first.size
    }

    private constructor(first: IntArray, second: DoubleArray, start: Int, end: Int) {
        this.first = first
        this.second = second
        this.start = start
        this.end = end
    }

    constructor(pairs: Collection<Pair<Int, Double>>, allowZero: Boolean=true) {
        val tempFirst = EntangledArrayUtils.INT(pairs.size)
        val tempSecond = EntangledArrayUtils.DOUBLE_2(pairs.size)
        var n = 0

        for ((first, second) in pairs) {
            tempFirst[n] = first
            tempSecond[n] = second
            n++
        }

        sort(tempFirst, tempSecond, n)
        val fused = fuse(tempFirst, tempSecond, n, allowZero)
        val effectiveSize = n - fused

        first = tempFirst.copyOf(effectiveSize)
        second = tempSecond.copyOf(effectiveSize)
        start = 0
        end = effectiveSize
    }

    /**
     * Scales every quantity by [multiplier], keeping all levels and entries (zeros are not dropped).
     * Returns [EMPTY] for a zero multiplier or empty vector, and `this` unchanged for a multiplier of
     * `1.0`. Levels are unchanged and [first] is immutable, so when this vector spans the whole backing
     * array the level array is shared with the result; otherwise the `[start, end)` window is copied.
     */
    operator fun times(multiplier: Double): IntDoubleEntangledArray {
        if (multiplier == 0.0 || size == 0) return EMPTY
        if (multiplier == 1.0) return this

        val outQuantities = DoubleArray(size)
        for (c in start until end) {
            outQuantities[c - start] = second[c] * multiplier
        }
        return IntDoubleEntangledArray(windowLevels(), outQuantities)
    }

    /**
     * Divides every quantity by [divisor], keeping all levels and entries. Returns [EMPTY] for an
     * empty vector and `this` unchanged for a divisor of `1.0`. Levels are unchanged and [first] is
     * immutable, so when this vector spans the whole backing array the level array is shared with the
     * result; otherwise the `[start, end)` window is copied.
     */
    operator fun div(divisor: Double): IntDoubleEntangledArray {
        if (size == 0) return EMPTY
        if (divisor == 1.0) return this

        val inv = 1 / divisor
        val outQuantities = DoubleArray(size)

        for (c in start until end) {
            outQuantities[c - start] = second[c] * inv
        }

        return IntDoubleEntangledArray(windowLevels(), outQuantities)
    }

    /**
     * The level window `[start, end)` as a standalone array, sharing the immutable [first] backing
     * array when this vector spans it whole and copying the `[start, end)` slice otherwise.
     */
    private fun windowLevels(): IntArray =
        if (size == first.size) first else first.copyOfRange(start, end)

    /**
     * Canonical absolute value : every quantity replaced by its magnitude, with any
     * zero entries dropped. The input is returned unchanged (same instance) when it already
     * carries no negative and no zero quantity — the common case for a freshly built array — so
     * the all-positive path costs a single scan and no allocation. An empty or all-zero array
     * yields [EMPTY]; otherwise the scratch buffers from [ArrayUtils] hold the survivors before
     * they are trimmed into the result.
     */
    fun absolute(): IntDoubleEntangledArray {
        if (size == 0) {
            return EMPTY
        }

        val levels = first
        val quantities = second

        // A strictly-positive array is already its own canonical absolute value.
        var clean = true
        for (i in start until end) {
            if (quantities[i] <= 0.0) { clean = false; break }
        }
        if (clean) {
            return this
        }

        val first = EntangledArrayUtils.INT(size)
        val second = EntangledArrayUtils.DOUBLE(size)
        var out = 0

        for (i in start until end) {
            val q = abs(quantities[i])
            if (q != 0.0) { first[out] = levels[i]; second[out] = q; out++ }
        }

        return result(first, second, out)
    }

    /**
     * Returns a view over the first [n] entries (lowest levels). No data is copied: the result
     * shares the backing arrays and only narrows the `[start, end)` window. A non-positive [n]
     * yields [EMPTY]; an [n] at or above [size] returns `this` unchanged.
     */
    fun head(n: Int): IntDoubleEntangledArray {
        if (n <= 0) return EMPTY
        if (n >= size) return this
        return IntDoubleEntangledArray(first, second, start, start + n)
    }

    /**
     * Returns a view over the last [n] entries (highest levels). No data is copied: the result
     * shares the backing arrays and only narrows the `[start, end)` window. A non-positive [n]
     * yields [EMPTY]; an [n] at or above [size] returns `this` unchanged.
     */
    fun tail(n: Int): IntDoubleEntangledArray {
        if (n <= 0) return EMPTY
        if (n >= size) return this
        return IntDoubleEntangledArray(first, second, end - n, end)
    }

    /**
     * Returns a view over the [n] middle entries, dropping the surplus entries evenly from both ends
     * (one extra from the high end when the surplus is odd). No data is copied: the result shares the
     * backing arrays and only narrows the `[start, end)` window. A non-positive [n] yields [EMPTY]; an
     * [n] at or above [size] returns `this` unchanged.
     */
    fun trim(n: Int): IntDoubleEntangledArray {
        if (n <= 0) return EMPTY
        if (n >= size) return this
        val newStart = start + (size - n) / 2
        return IntDoubleEntangledArray(first, second, newStart, newStart + n)
    }

    /**
     * Returns a view that keeps only the entries whose level is `<= [num]`, dropping every entry above
     * [num]. No data is copied: the result shares the backing arrays and only narrows the `[start, end)`
     * window. Yields [EMPTY] when no entry qualifies and returns `this` unchanged when none is dropped.
     */
    fun dropAfter(num: Int): IntDoubleEntangledArray {
        val newEnd = upperBound(num)
        if (newEnd <= start) return EMPTY
        if (newEnd == end) return this
        return IntDoubleEntangledArray(first, second, start, newEnd)
    }

    /**
     * Returns a view that keeps only the entries whose level is `< [num]`, dropping [num] and every
     * entry above it. No data is copied: the result shares the backing arrays and only narrows the
     * `[start, end)` window. Yields [EMPTY] when no entry qualifies and returns `this` unchanged when
     * none is dropped.
     */
    fun dropStrictAfter(num: Int): IntDoubleEntangledArray {
        val newEnd = lowerBound(num)
        if (newEnd <= start) return EMPTY
        if (newEnd == end) return this
        return IntDoubleEntangledArray(first, second, start, newEnd)
    }

    /**
     * Returns a view that keeps only the entries whose level is `>= [num]`, dropping every entry below
     * [num]. No data is copied: the result shares the backing arrays and only narrows the `[start, end)`
     * window. Yields [EMPTY] when no entry qualifies and returns `this` unchanged when none is dropped.
     */
    fun dropBefore(num: Int): IntDoubleEntangledArray {
        val newStart = lowerBound(num)
        if (newStart >= end) return EMPTY
        if (newStart == start) return this
        return IntDoubleEntangledArray(first, second, newStart, end)
    }

    /**
     * Returns a view that keeps only the entries whose level is `> [num]`, dropping [num] and every
     * entry below it. No data is copied: the result shares the backing arrays and only narrows the
     * `[start, end)` window. Yields [EMPTY] when no entry qualifies and returns `this` unchanged when
     * none is dropped.
     */
    fun dropStrictBefore(num: Int): IntDoubleEntangledArray {
        val newStart = upperBound(num)
        if (newStart >= end) return EMPTY
        if (newStart == start) return this
        return IntDoubleEntangledArray(first, second, newStart, end)
    }

    /** First index in `[start, end)` whose level is `>= [num]`, or [end] if none (levels are ascending). */
    private fun lowerBound(num: Int): Int {
        var lo = start
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (first[mid] < num) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index in `[start, end)` whose level is `> [num]`, or [end] if none (levels are ascending). */
    private fun upperBound(num: Int): Int {
        var lo = start
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (first[mid] <= num) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Serializes this array into a compact byte form, the inverse of [deserialize]. The four leading
     * bytes hold the entry count; an empty array serializes to just those four zero bytes. Otherwise
     * the entries are grouped into runs of consecutive levels (see [ArrayIOUtils.computePackets]) and
     * each run is written as a magic byte (the packed run-length and start-level widths), the run
     * length, its starting level, and one IEEE-754 double per quantity — levels themselves are never
     * stored, since they are recovered as `startLevel + i`.
     */
    fun serialize(): ByteArray {
        if (size == 0) {
            return ByteBuffer.allocate(4).putInt(0).array()
        }

        val levels = first
        val quantities = second
        val packets = ArrayIOUtils.computePackets(levels, start, end)

        var totalBytes = 4
        for (p in packets) {
            val packetSize = p.endExclusive - p.start
            val startLevel = levels[p.start]
            totalBytes += 1 +                            // magic byte
                ArrayIOUtils.widthOf(packetSize) +
                ArrayIOUtils.widthOf(startLevel) +
                packetSize * 8                           // each quantity is a Double (8 bytes)
        }

        val buffer = ByteBuffer.allocate(totalBytes)
        buffer.putInt(size)

        for (p in packets) {
            val packetSize = p.endExclusive - p.start
            val startLevel = levels[p.start]
            val sizeWidth = ArrayIOUtils.widthOf(packetSize)
            val startWidth = ArrayIOUtils.widthOf(startLevel)

            buffer.put(ArrayIOUtils.packMagic(sizeWidth, startWidth))
            ArrayIOUtils.putInt(buffer, packetSize, sizeWidth)
            ArrayIOUtils.putInt(buffer, startLevel, startWidth)
            for (i in p.start until p.endExclusive) {
                buffer.putDouble(quantities[i])
            }
        }

        return buffer.array()
    }

    companion object {
        val EMPTY = IntDoubleEntangledArray(IntArray(0), DoubleArray(0))
        private const val INSERTION_THRESHOLD = 40

        /**
         * Aggregates the `(level, quantity)` window `[from, to)` of [first]/[second] into logarithmic
         * buckets, summing the quantities that land in each bucket. Each level `f` maps to the integer
         * bucket `(ln(f) * invLnExp).toInt()`, and the [second] values of all entries sharing a bucket
         * are summed. The result pairs every surviving bucket with its total in ascending bucket order;
         * a bucket whose total is exactly `0.0` is dropped, so an empty window — or one that cancels away
         * entirely — yields [EMPTY]. See [DoubleDoubleEntangledArray.zip] for the full contract.
         *
         * [first] is required to hold strictly ascending, strictly positive levels (as for prices) and
         * [invLnExp] to be positive, so `ln` is monotonic over the window and the bucket index is
         * non-decreasing as the scan advances. Equal buckets therefore form contiguous runs: a single
         * linear pass accumulates each run and emits it when the bucket changes, with no sorting,
         * hashing, or extra allocation beyond the trimmed result.
         */
        fun zip(first: DoubleArray, second: DoubleArray, from: Int, to: Int, invLnExp: Double): IntDoubleEntangledArray {
            require(first.size == second.size) { "first size must be equal to second size" }
            Objects.checkFromToIndex(from, to, first.size)

            if (from == to) {
                return EMPTY
            }

            val n = to - from
            val outLevels = EntangledArrayUtils.INT(n)
            val outQuantities = EntangledArrayUtils.DOUBLE(n)
            var out = 0

            var currentBucket = (ln(first[from]) * invLnExp).toInt()
            var currentQuantity = 0.0

            for (i in from until to) {
                val bucket = (ln(first[i]) * invLnExp).toInt()

                if (bucket == currentBucket) {
                    currentQuantity += second[i]
                } else {
                    if (currentQuantity != 0.0) {
                        outLevels[out] = currentBucket
                        outQuantities[out] = currentQuantity
                        out++
                    }
                    currentBucket = bucket
                    currentQuantity = second[i]
                }
            }

            if (currentQuantity != 0.0) {
                outLevels[out] = currentBucket
                outQuantities[out] = currentQuantity
                out++
            }

            return result(outLevels, outQuantities, out)
        }

        /**
         * Reconstructs an array from the byte form produced by [serialize]. The leading four bytes
         * give the entry count; a count of `0` yields [EMPTY]. The remaining bytes are decoded
         * packet by packet, each level recovered as `startLevel + i` and each quantity read as a
         * double. The bytes are trusted to describe a canonical array (ascending levels, no
         * duplicates, no zeros), so the result is built directly with no re-sorting or fusing. A
         * truncated or malformed input raises [IllegalArgumentException].
         */
        fun deserialize(bytes: ByteArray): IntDoubleEntangledArray {
            val buffer = ByteBuffer.wrap(bytes)
            val totalSize = try {
                buffer.getInt()
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid byte array: cannot read total size", e)
            }

            if (totalSize == 0) {
                return EMPTY
            }

            val levels = IntArray(totalSize)
            val quantities = DoubleArray(totalSize)
            var index = 0

            try {
                while (index < totalSize) {
                    val magic = buffer.get().toInt() and 0xFF
                    val sizeWidth = ArrayIOUtils.widthAt(magic, 0)
                    val startWidth = ArrayIOUtils.widthAt(magic, 1)

                    val packetSize = ArrayIOUtils.readInt(buffer, sizeWidth)
                    val startLevel = ArrayIOUtils.readInt(buffer, startWidth)
                    for (i in 0 until packetSize) {
                        levels[index + i] = startLevel + i
                        quantities[index + i] = buffer.getDouble()
                    }
                    index += packetSize
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid byte array: cannot decode packets", e)
            }

            return IntDoubleEntangledArray(levels, quantities)
        }

        /**
         * Trims the scratch buffers down to the [out] entries written so far, yielding the [EMPTY]
         * singleton when nothing survived. Called once per operation, not per entry.
         */
        private fun result(first: IntArray, second: DoubleArray, out: Int): IntDoubleEntangledArray {
            if (out == 0) {
                return EMPTY
            }
            return IntDoubleEntangledArray(first.copyOf(out), second.copyOf(out))
        }

        /**
         * Compacts a dense scatter buffer used by the set/overlay paths, whose absent levels hold
         * [Double.NaN] so that a genuine `0.0` stays distinct from "no input carried this level".
         * Levels no input carried (NaN) are always skipped; a genuine `0.0` is dropped only when
         * [dropZeros]. Survivors are written back into [first]/[second] in place.
         */
        private fun collectDenseSet(first: IntArray, second: DoubleArray, minLevel: Int, width: Int, dropZeros: Boolean): IntDoubleEntangledArray {
            var out = 0
            for (k in 0 until width) {
                val q = second[k]
                if (q == q && (!dropZeros || q != 0.0)) {
                    first[out] = minLevel + k
                    second[out] = q
                    out++
                }
            }
            return result(first, second, out)
        }

        /**
         * Compacts a dense scatter buffer used by the weighted-sum paths, whose absent levels hold
         * `0.0` (the additive identity). Surviving non-zero levels are written back into
         * [first]/[second] in place.
         */
        private fun collectDenseSum(first: IntArray, second: DoubleArray, minLevel: Int, width: Int): IntDoubleEntangledArray {
            var out = 0
            for (k in 0 until width) {
                val q = second[k]
                if (q != 0.0) {
                    first[out] = minLevel + k
                    second[out] = q
                    out++
                }
            }
            return result(first, second, out)
        }

        private fun classicSort(levels: IntArray, quantities: DoubleArray, end: Int) {
            for (i in 1 until end) {
                val lv = levels[i]
                val q = quantities[i]
                var j = i - 1
                while (j >= 0 && levels[j] > lv) {
                    levels[j + 1] = levels[j]
                    quantities[j + 1] = quantities[j]
                    j--
                }
                levels[j + 1] = lv
                quantities[j + 1] = q
            }
        }

        private fun sort(levels: IntArray, quantities: DoubleArray, end: Int) {
            if (end <= 1) return

            val sorted = levels.sortedDirection(0, end)

            if (sorted == ArrayUtils.Sorted.ASC) {
                return
            }
            else if (sorted == ArrayUtils.Sorted.DESC) {
                levels.reverse(0, end)
                quantities.reverse(0, end)
                return
            }

            if (end <= INSERTION_THRESHOLD) {
                classicSort(levels, quantities, end)
                return
            }

            val keys = EntangledArrayUtils.LONG(end)

            for (i in 0 until end) {
                keys[i] = (levels[i].toLong() shl 32) or (i.toLong() and 0xFFFFFFFFL)
            }
            Arrays.sort(keys, 0, end)

            for (i in 0 until end) {
                levels[i] = (keys[i] shr 32).toInt()
            }

            val scratch = EntangledArrayUtils.DOUBLE(end)
            for (i in 0 until end) {
                scratch[i] = quantities[(keys[i] and 0xFFFFFFFFL).toInt()]
            }
            System.arraycopy(scratch, 0, quantities, 0, end)
        }

        private fun fuse(levels: IntArray, quantities: DoubleArray, end: Int, allowZero: Boolean): Int {
            var out = 0
            while (out < end) {
                if (out + 1 < end && levels[out + 1] == levels[out]) break
                if (!allowZero && quantities[out] == 0.0) break
                out++
            }

            var read = out
            while (read < end) {
                val level = levels[read]
                var quantity = quantities[read]
                read++
                while (read < end && levels[read] == level) {
                    quantity += quantities[read]
                    read++
                }
                if (allowZero || quantity != 0.0) {
                    levels[out] = level
                    quantities[out] = quantity
                    out++
                }
            }

            return end - out
        }

        /**
         * Returns the level-by-level absolute difference of two arrays: for every level present in
         * either input the result carries `|q_a − q_b|`, where a level absent from one side counts as
         * `0.0` on that side — so a level held only by [a] yields `|q_a|` and one held only by [b]
         * yields `|q_b|`. Levels whose two quantities are equal cancel to `0.0` and are dropped (as
         * does a `0.0`-against-absent entry), so the result is always canonical (ascending levels, no
         * zeros, no duplicate levels) and is the [EMPTY] singleton when the two arrays are equal or
         * both empty.
         *
         * The operation is symmetric: `absoluteDiff(a, b)` and `absoluteDiff(b, a)` produce equal
         * results.
         */
        fun absoluteDiff(a: IntDoubleEntangledArray, b: IntDoubleEntangledArray): IntDoubleEntangledArray {
            if (a.size == 0) return b.absolute()
            if (b.size == 0) return a.absolute()

            val s = a.size + b.size
            val first = EntangledArrayUtils.INT(s)
            val second = EntangledArrayUtils.DOUBLE(s)

            val levelsA = a.first
            val quantitiesA = a.second
            val levelsB = b.first
            val quantitiesB = b.second
            var i = a.start
            var j = b.start
            val endA = a.end
            val endB = b.end
            var out = 0

            while (i < endA && j < endB) {
                val la = levelsA[i]
                val lb = levelsB[j]
                when {
                    la < lb -> {
                        val q = abs(quantitiesA[i])
                        if (q != 0.0) { first[out] = la; second[out] = q; out++ }
                        i++
                    }
                    la > lb -> {
                        val q = abs(quantitiesB[j])
                        if (q != 0.0) { first[out] = lb; second[out] = q; out++ }
                        j++
                    }
                    else -> {
                        val q = abs(quantitiesA[i] - quantitiesB[j])
                        if (q != 0.0) { first[out] = la; second[out] = q; out++ }
                        i++; j++
                    }
                }
            }
            while (i < endA) {
                val q = abs(quantitiesA[i])
                if (q != 0.0) { first[out] = levelsA[i]; second[out] = q; out++ }
                i++
            }
            while (j < endB) {
                val q = abs(quantitiesB[j])
                if (q != 0.0) { first[out] = levelsB[j]; second[out] = q; out++ }
                j++
            }

            return result(first, second, out)
        }

        /**
         * Overlays N entangled arrays into one, applied left-to-right so that later arrays set the
         * value at each level: for inputs `A, B, C` the result is `A.set(B).set(C)`, computed in a
         * single pass rather than by building intermediates. For any level the quantity comes from the
         * last input that carries it (last-writer-wins); a level present in only some inputs is kept
         * with that input's quantity. Unlike [weightedSum], shared levels are *replaced*, not summed.
         * Empty arrays are skipped; the result is [EMPTY] when no surviving entry remains.
         *
         * By default ([dropZeros] = `true`) entries whose winning quantity is `0.0` are dropped, so the
         * result stays canonical; when exactly one non-empty input remains it is returned unchanged if
         * it has no zero entries, otherwise its zeros are dropped too. Pass [dropZeros] = `false` to keep
         * every level that at least one input carries, including those whose winning quantity is `0.0`
         * (a level absent from every input is still never emitted).
         */
        fun cumulativeSet(inputs: Collection<IntDoubleEntangledArray>, dropZeros: Boolean = true): IntDoubleEntangledArray {
            val initSize = inputs.size
            val arrays = Array(initSize) { EMPTY }

            var n = 0
            var minLevel: Int = Int.MAX_VALUE
            var maxLevel: Int = Int.MIN_VALUE
            var s = 0

            for (array in inputs) {
                if (array.size == 0) {
                    continue
                }

                arrays[n] = array
                s += array.size
                n++
                minLevel = min(minLevel, array.firstLevel)
                maxLevel = max(maxLevel, array.lastLevel)
            }

            if (n == 0) {
                return EMPTY
            }
            else if (n == 1 && !dropZeros) {
                return arrays[0]
            }

            val first = EntangledArrayUtils.INT(s)
            val second = EntangledArrayUtils.DOUBLE(s)

            return when(n) {
                1 -> cumulativeSet1(arrays[0], first, second, dropZeros)
                2 -> cumulativeSet2(arrays[0], arrays[1], first, second, minLevel, maxLevel, s, dropZeros)
                // mergeSetN's heap can't tolerate trailing EMPTY slots, so drop them when inputs were skipped.
                else -> cumulativeSetN(arrays, n, first, second, minLevel, maxLevel, s, dropZeros)
            }
        }

        private fun cumulativeSet1(array: IntDoubleEntangledArray, first: IntArray, second: DoubleArray, dropZeros: Boolean): IntDoubleEntangledArray {
            val srcLevels = array.first
            val srcQuantities = array.second
            var out = 0

            for (i in array.start until array.end) {
                val q = srcQuantities[i]
                if (!dropZeros || q != 0.0) {
                    first[out] = srcLevels[i]
                    second[out] = q
                    out++
                }
            }

            if (out == array.size) {
                return array
            }

            return result(first, second, out)
        }

        private fun cumulativeSet2(
            a: IntDoubleEntangledArray, b: IntDoubleEntangledArray,
            first: IntArray, second: DoubleArray,
            minLevel: Int, maxLevel: Int, s: Int, dropZeros: Boolean,
        ): IntDoubleEntangledArray {
            val span = maxLevel.toLong() - minLevel

            return if ((span + 1) * 10 <= s.toLong() * 9) {
                denseSet2(a, b, first, second, minLevel, span.toInt(), dropZeros)
            }
            else {
                mergeSet2(a, b, first, second, dropZeros)
            }
        }

        private fun denseSet2(
            a: IntDoubleEntangledArray, b: IntDoubleEntangledArray,
            first: IntArray, second: DoubleArray,
            minLevel: Int, span: Int, dropZeros: Boolean,
        ): IntDoubleEntangledArray {
            val width = span + 1
            // NaN marks a level no input carries, so a later "set to 0.0" stays distinct from "absent".
            second.fill(Double.NaN, 0, width)

            val levelsA = a.first
            val quantitiesA = a.second
            for (i in a.start until a.end) {
                second[levelsA[i] - minLevel] = quantitiesA[i]
            }
            val levelsB = b.first
            val quantitiesB = b.second
            for (j in b.start until b.end) {
                second[levelsB[j] - minLevel] = quantitiesB[j]
            }

            return collectDenseSet(first, second, minLevel, width, dropZeros)
        }

        private fun mergeSet2(
            a: IntDoubleEntangledArray, b: IntDoubleEntangledArray,
            first: IntArray, second: DoubleArray, dropZeros: Boolean,
        ): IntDoubleEntangledArray {
            val levelsA = a.first
            val quantitiesA = a.second
            val levelsB = b.first
            val quantitiesB = b.second
            var i = a.start
            var j = b.start
            val endA = a.end
            val endB = b.end
            var out = 0

            while (i < endA && j < endB) {
                val la = levelsA[i]
                val lb = levelsB[j]
                when {
                    la < lb -> {
                        val q = quantitiesA[i]
                        if (!dropZeros || q != 0.0) { first[out] = la; second[out] = q; out++ }
                        i++
                    }
                    la > lb -> {
                        val q = quantitiesB[j]
                        if (!dropZeros || q != 0.0) { first[out] = lb; second[out] = q; out++ }
                        j++
                    }
                    else -> {
                        val q = quantitiesB[j]
                        if (!dropZeros || q != 0.0) { first[out] = la; second[out] = q; out++ }
                        i++; j++
                    }
                }
            }
            while (i < endA) {
                val q = quantitiesA[i]
                if (!dropZeros || q != 0.0) { first[out] = levelsA[i]; second[out] = q; out++ }
                i++
            }
            while (j < endB) {
                val q = quantitiesB[j]
                if (!dropZeros || q != 0.0) { first[out] = levelsB[j]; second[out] = q; out++ }
                j++
            }

            return result(first, second, out)
        }

        private fun cumulativeSetN(
            arrays: Array<IntDoubleEntangledArray>, n: Int,
            first: IntArray, second: DoubleArray,
            minLevel: Int, maxLevel: Int, s: Int, dropZeros: Boolean,
        ): IntDoubleEntangledArray {
            val span = maxLevel.toLong() - minLevel

            return if (span + 1 <= s) {
                denseSetN(arrays, n, first, second, minLevel, span.toInt(), dropZeros)
            }
            else {
                mergeSetN(arrays, n, first, second, dropZeros)
            }
        }

        private fun denseSetN(
            arrays: Array<IntDoubleEntangledArray>, n: Int,
            first: IntArray, second: DoubleArray,
            minLevel: Int, span: Int, dropZeros: Boolean,
        ): IntDoubleEntangledArray {
            val width = span + 1
            // NaN marks a level no input carries, so a later "set to 0.0" stays distinct from "absent".
            second.fill(Double.NaN, 0, width)

            // Earlier arrays first, later arrays overwrite: last writer wins per level.
            for (k in 0 until n) {
                val array = arrays[k]
                val levels = array.first
                val quantities = array.second
                for (i in array.start until array.end) {
                    second[levels[i] - minLevel] = quantities[i]
                }
            }

            return collectDenseSet(first, second, minLevel, width, dropZeros)
        }

        /**
         * Sparse-path N-way overlay via a k-way min-heap of the inputs' current heads, keyed on the
         * cached front level of each array. Runs in O(s · log N) versus the dense path's O(span); a
         * benchmark across N showed the cached heap beats the previous linear find-min scan from N=3
         * up (≈1.3× at N=8, ≈3–4× at N≥32) and ties it at N=2. Last-writer-wins is preserved by
         * draining every head that shares the current minimum level and keeping the largest array
         * index. Requires [arrays] to be compacted (no empty entries), which [cumulativeSet] ensures.
         */
        private fun mergeSetN(
            arrays: Array<IntDoubleEntangledArray>, n: Int,
            first: IntArray, second: DoubleArray, dropZeros: Boolean,
        ): IntDoubleEntangledArray {
            val heads = IntArray(n)
            val frontLevel = IntArray(n)
            val heap = IntArray(n) { it }
            for (k in 0 until n) {
                val a = arrays[k]
                frontLevel[k] = a.first[a.start]
            }
            var m = n

            fun siftDown(from: Int) {
                var i = from
                val node = heap[i]
                val nodeKey = frontLevel[node]
                while (true) {
                    var child = 2 * i + 1
                    if (child >= m) break
                    var childNode = heap[child]
                    var childKey = frontLevel[childNode]
                    val right = child + 1
                    if (right < m) {
                        val rNode = heap[right]
                        val rKey = frontLevel[rNode]
                        if (rKey < childKey) { child = right; childNode = rNode; childKey = rKey }
                    }
                    if (childKey >= nodeKey) break
                    heap[i] = childNode
                    i = child
                }
                heap[i] = node
            }

            for (i in n / 2 - 1 downTo 0) siftDown(i)

            var out = 0
            while (m > 0) {
                val level = frontLevel[heap[0]]
                var bestK = -1
                var bestQ = 0.0
                // Drain every head sitting on this level; ascending bestK keeps the last writer.
                while (m > 0 && frontLevel[heap[0]] == level) {
                    val k = heap[0]
                    val a = arrays[k]
                    val h = a.start + heads[k]
                    if (k > bestK) { bestK = k; bestQ = a.second[h] }
                    heads[k]++
                    val nh = a.start + heads[k]
                    if (nh < a.end) {
                        frontLevel[k] = a.first[nh]
                        siftDown(0)
                    } else {
                        m--
                        heap[0] = heap[m]
                        if (m > 0) siftDown(0)
                    }
                }
                if (!dropZeros || bestQ != 0.0) { first[out] = level; second[out] = bestQ; out++ }
            }

            return result(first, second, out)
        }

        /**
         * Merges N entangled arrays into one, summing each level's quantity weighted by the
         * accompanying multiplier: the result quantity for a level is `Σ arrayₖ[ level ] * multiplierₖ`
         * over every input that carries it. A level present in only some inputs still contributes.
         * Entries whose final weighted quantity is `0.0` are dropped, and an array whose multiplier
         * is `0.0` is ignored outright (it can only contribute zeros).
         */
        fun weightedSum(pairs: Collection<Pair<IntDoubleEntangledArray, Double>>): IntDoubleEntangledArray {
            val initSize = pairs.size
            val arrays = Array(initSize) { EMPTY }
            val multipliers = DoubleArray(initSize) { 0.0 }

            var n = 0
            var minLevel = Int.MAX_VALUE
            var maxLevel = Int.MIN_VALUE
            var s = 0

            for ((array, multiplier) in pairs) {
                if (multiplier == 0.0 || array.size == 0) {
                    continue
                }

                arrays[n] = array
                multipliers[n] = multiplier
                n++
                s += array.size
                minLevel = min(minLevel, array.firstLevel)
                maxLevel = max(maxLevel, array.lastLevel)
            }

            if (n == 0) {
                return EMPTY
            }

            val first = EntangledArrayUtils.INT(s)
            val second = EntangledArrayUtils.DOUBLE(s)

            return when (n) {
                1 -> weightedSum1(arrays[0], multipliers[0], first, second)
                2 -> weightedSum2(arrays[0], arrays[1], multipliers[0], multipliers[1], first, second, minLevel, maxLevel, s)
                else -> weightedSumN(arrays, multipliers, first, second, minLevel, maxLevel, s)
            }
        }

        private fun weightedSum1(
            array: IntDoubleEntangledArray, multiplier: Double,
            scratchFirst: IntArray, scratchSecond: DoubleArray,
        ): IntDoubleEntangledArray {
            val srcLevels = array.first
            val srcQuantities = array.second
            var out = 0

            for (i in array.start until array.end) {
                val q = srcQuantities[i] * multiplier

                if (q != 0.0) {
                    scratchFirst[out] = srcLevels[i]
                    scratchSecond[out] = q
                    out++
                }
            }

            if (out == 0) {
                return EMPTY
            }
            // The window already spans the whole source: reuse its levels (and the array itself
            // when the multiplier left every quantity untouched) rather than copying.
            if (out == srcLevels.size) {
                if (multiplier == 1.0) {
                    return array
                }
                return IntDoubleEntangledArray(srcLevels, scratchSecond.copyOf(out))
            }

            return IntDoubleEntangledArray(scratchFirst.copyOf(out), scratchSecond.copyOf(out))
        }

        private fun weightedSum2(
            a: IntDoubleEntangledArray, b: IntDoubleEntangledArray,
            multiplierA: Double, multiplierB: Double,
            first: IntArray, second: DoubleArray,
            minLevel: Int, maxLevel: Int, s: Int,
        ): IntDoubleEntangledArray {
            val span = maxLevel.toLong() - minLevel

            return if ((span + 1) * 10 <= s.toLong() * 9) {
                denseSum2(a, b, multiplierA, multiplierB, first, second, minLevel, span.toInt())
            }
            else {
                mergeSum2(a, b, multiplierA, multiplierB, first, second)
            }
        }

        private fun denseSum2(
            a: IntDoubleEntangledArray, b: IntDoubleEntangledArray,
            multiplierA: Double, multiplierB: Double,
            first: IntArray, second: DoubleArray,
            minLevel: Int, span: Int,
        ): IntDoubleEntangledArray {
            val width = span + 1
            second.fill(0.0, 0, width)

            val levelsA = a.first
            val quantitiesA = a.second
            for (i in a.start until a.end) {
                second[levelsA[i] - minLevel] += quantitiesA[i] * multiplierA
            }
            val levelsB = b.first
            val quantitiesB = b.second
            for (j in b.start until b.end) {
                second[levelsB[j] - minLevel] += quantitiesB[j] * multiplierB
            }

            return collectDenseSum(first, second, minLevel, width)
        }

        private fun mergeSum2(
            a: IntDoubleEntangledArray, b: IntDoubleEntangledArray,
            multiplierA: Double, multiplierB: Double,
            first: IntArray, second: DoubleArray,
        ): IntDoubleEntangledArray {
            val levelsA = a.first
            val quantitiesA = a.second
            val levelsB = b.first
            val quantitiesB = b.second
            var i = a.start
            var j = b.start
            val endA = a.end
            val endB = b.end
            var out = 0

            while (i < endA && j < endB) {
                val la = levelsA[i]
                val lb = levelsB[j]
                when {
                    la < lb -> {
                        val q = quantitiesA[i] * multiplierA
                        if (q != 0.0) { first[out] = la; second[out] = q; out++ }
                        i++
                    }
                    la > lb -> {
                        val q = quantitiesB[j] * multiplierB
                        if (q != 0.0) { first[out] = lb; second[out] = q; out++ }
                        j++
                    }
                    else -> {
                        val q = quantitiesA[i] * multiplierA + quantitiesB[j] * multiplierB
                        if (q != 0.0) { first[out] = la; second[out] = q; out++ }
                        i++; j++
                    }
                }
            }
            while (i < endA) {
                val q = quantitiesA[i] * multiplierA
                if (q != 0.0) { first[out] = levelsA[i]; second[out] = q; out++ }
                i++
            }
            while (j < endB) {
                val q = quantitiesB[j] * multiplierB
                if (q != 0.0) { first[out] = levelsB[j]; second[out] = q; out++ }
                j++
            }

            return result(first, second, out)
        }

        private fun weightedSumN(
            arrays: Array<IntDoubleEntangledArray>, multipliers: DoubleArray,
            first: IntArray, second: DoubleArray,
            minLevel: Int, maxLevel: Int, s: Int,
        ): IntDoubleEntangledArray {
            val span = maxLevel.toLong() - minLevel

            return if (span + 1 <= s) {
                denseSumN(arrays, multipliers, first, second, minLevel, span.toInt())
            }
            else {
                mergeSumN(arrays, multipliers, first, second)
            }
        }

        private fun denseSumN(
            arrays: Array<IntDoubleEntangledArray>, multipliers: DoubleArray,
            first: IntArray, second: DoubleArray,
            minLevel: Int, span: Int,
        ): IntDoubleEntangledArray {
            val width = span + 1
            second.fill(0.0, 0, width)

            for (k in arrays.indices) {
                val array = arrays[k]
                val levels = array.first
                val quantities = array.second
                val multiplier = multipliers[k]
                for (i in array.start until array.end) {
                    second[levels[i] - minLevel] += quantities[i] * multiplier
                }
            }

            return collectDenseSum(first, second, minLevel, width)
        }

        private fun mergeSumN(
            arrays: Array<IntDoubleEntangledArray>, multipliers: DoubleArray,
            first: IntArray, second: DoubleArray,
        ): IntDoubleEntangledArray {
            val n = arrays.size
            val heads = IntArray(n)
            var out = 0

            while (true) {
                var minLevel = 0
                var found = false
                for (k in 0 until n) {
                    val array = arrays[k]
                    val h = array.start + heads[k]
                    if (h < array.end) {
                        val level = array.first[h]
                        if (!found || level < minLevel) { minLevel = level; found = true }
                    }
                }
                if (!found) {
                    break
                }

                var q = 0.0
                for (k in 0 until n) {
                    val array = arrays[k]
                    val h = array.start + heads[k]
                    if (h < array.end && array.first[h] == minLevel) {
                        q += array.second[h] * multipliers[k]
                        heads[k]++
                    }
                }
                if (q != 0.0) { first[out] = minLevel; second[out] = q; out++ }
            }

            return result(first, second, out)
        }

        /**
         * Sums N entangled arrays into one, adding each level's quantity across every input that
         * carries it: the result quantity for a level is `Σ arrayₖ[ level ]`. This is [weightedSum]
         * with every multiplier fixed at `1.0`, specialized to skip the per-entry multiply and the
         * boxed `(array, multiplier)` pairs the weighted form requires. Empty arrays are ignored and a
         * level summing to `0.0` is dropped; a single already-canonical input is returned unchanged.
         */
        fun sum(arrays: Collection<IntDoubleEntangledArray>): IntDoubleEntangledArray {
            val initSize = arrays.size
            val kept = Array(initSize) { EMPTY }

            var n = 0
            var minLevel = Int.MAX_VALUE
            var maxLevel = Int.MIN_VALUE
            var s = 0

            for (array in arrays) {
                if (array.size == 0) {
                    continue
                }

                kept[n] = array
                n++
                s += array.size
                minLevel = min(minLevel, array.firstLevel)
                maxLevel = max(maxLevel, array.lastLevel)
            }

            if (n == 0) {
                return EMPTY
            }

            val first = EntangledArrayUtils.INT(s)
            val second = EntangledArrayUtils.DOUBLE(s)

            return when (n) {
                1 -> sum1(kept[0], first, second)
                2 -> sum2(kept[0], kept[1], first, second, minLevel, maxLevel, s)
                else -> sumN(kept, first, second, minLevel, maxLevel, s)
            }
        }

        private fun sum1(
            array: IntDoubleEntangledArray,
            scratchFirst: IntArray, scratchSecond: DoubleArray,
        ): IntDoubleEntangledArray {
            val srcLevels = array.first
            val srcQuantities = array.second
            var out = 0

            for (i in array.start until array.end) {
                val q = srcQuantities[i]

                if (q != 0.0) {
                    scratchFirst[out] = srcLevels[i]
                    scratchSecond[out] = q
                    out++
                }
            }

            if (out == 0) {
                return EMPTY
            }
            // Every entry survived and the window already spans the whole backing array → reuse it.
            if (out == srcLevels.size) {
                return array
            }

            return IntDoubleEntangledArray(scratchFirst.copyOf(out), scratchSecond.copyOf(out))
        }

        private fun sum2(
            a: IntDoubleEntangledArray, b: IntDoubleEntangledArray,
            first: IntArray, second: DoubleArray,
            minLevel: Int, maxLevel: Int, s: Int,
        ): IntDoubleEntangledArray {
            val span = maxLevel.toLong() - minLevel

            return if ((span + 1) * 10 <= s.toLong() * 9) {
                denseSumOf2(a, b, first, second, minLevel, span.toInt())
            }
            else {
                mergeSumOf2(a, b, first, second)
            }
        }

        private fun denseSumOf2(
            a: IntDoubleEntangledArray, b: IntDoubleEntangledArray,
            first: IntArray, second: DoubleArray,
            minLevel: Int, span: Int,
        ): IntDoubleEntangledArray {
            val width = span + 1
            second.fill(0.0, 0, width)

            val levelsA = a.first
            val quantitiesA = a.second
            for (i in a.start until a.end) {
                second[levelsA[i] - minLevel] += quantitiesA[i]
            }
            val levelsB = b.first
            val quantitiesB = b.second
            for (j in b.start until b.end) {
                second[levelsB[j] - minLevel] += quantitiesB[j]
            }

            return collectDenseSum(first, second, minLevel, width)
        }

        private fun mergeSumOf2(
            a: IntDoubleEntangledArray, b: IntDoubleEntangledArray,
            first: IntArray, second: DoubleArray,
        ): IntDoubleEntangledArray {
            val levelsA = a.first
            val quantitiesA = a.second
            val levelsB = b.first
            val quantitiesB = b.second
            var i = a.start
            var j = b.start
            val endA = a.end
            val endB = b.end
            var out = 0

            while (i < endA && j < endB) {
                val la = levelsA[i]
                val lb = levelsB[j]
                when {
                    la < lb -> {
                        val q = quantitiesA[i]
                        if (q != 0.0) { first[out] = la; second[out] = q; out++ }
                        i++
                    }
                    la > lb -> {
                        val q = quantitiesB[j]
                        if (q != 0.0) { first[out] = lb; second[out] = q; out++ }
                        j++
                    }
                    else -> {
                        val q = quantitiesA[i] + quantitiesB[j]
                        if (q != 0.0) { first[out] = la; second[out] = q; out++ }
                        i++; j++
                    }
                }
            }
            while (i < endA) {
                val q = quantitiesA[i]
                if (q != 0.0) { first[out] = levelsA[i]; second[out] = q; out++ }
                i++
            }
            while (j < endB) {
                val q = quantitiesB[j]
                if (q != 0.0) { first[out] = levelsB[j]; second[out] = q; out++ }
                j++
            }

            return result(first, second, out)
        }

        private fun sumN(
            arrays: Array<IntDoubleEntangledArray>,
            first: IntArray, second: DoubleArray,
            minLevel: Int, maxLevel: Int, s: Int,
        ): IntDoubleEntangledArray {
            val span = maxLevel.toLong() - minLevel

            return if (span + 1 <= s) {
                denseSumOfN(arrays, first, second, minLevel, span.toInt())
            }
            else {
                mergeSumOfN(arrays, first, second)
            }
        }

        private fun denseSumOfN(
            arrays: Array<IntDoubleEntangledArray>,
            first: IntArray, second: DoubleArray,
            minLevel: Int, span: Int,
        ): IntDoubleEntangledArray {
            val width = span + 1
            second.fill(0.0, 0, width)

            for (k in arrays.indices) {
                val array = arrays[k]
                val levels = array.first
                val quantities = array.second
                for (i in array.start until array.end) {
                    second[levels[i] - minLevel] += quantities[i]
                }
            }

            return collectDenseSum(first, second, minLevel, width)
        }

        private fun mergeSumOfN(
            arrays: Array<IntDoubleEntangledArray>,
            first: IntArray, second: DoubleArray,
        ): IntDoubleEntangledArray {
            val n = arrays.size
            val heads = IntArray(n)
            var out = 0

            while (true) {
                var minLevel = 0
                var found = false
                for (k in 0 until n) {
                    val array = arrays[k]
                    val h = array.start + heads[k]
                    if (h < array.end) {
                        val level = array.first[h]
                        if (!found || level < minLevel) { minLevel = level; found = true }
                    }
                }
                if (!found) {
                    break
                }

                var q = 0.0
                for (k in 0 until n) {
                    val array = arrays[k]
                    val h = array.start + heads[k]
                    if (h < array.end && array.first[h] == minLevel) {
                        q += array.second[h]
                        heads[k]++
                    }
                }
                if (q != 0.0) { first[out] = minLevel; second[out] = q; out++ }
            }

            return result(first, second, out)
        }
    }

}