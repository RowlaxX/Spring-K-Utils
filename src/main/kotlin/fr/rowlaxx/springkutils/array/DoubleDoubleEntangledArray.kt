package fr.rowlaxx.springkutils.array

import fr.rowlaxx.springkutils.array.ArrayUtils.sortedDirection
import fr.rowlaxx.springkutils.array.DoubleDoubleEntangledArray.Companion.EMPTY
import fr.rowlaxx.springkutils.array.DoubleDoubleEntangledArray.Companion.cumulativeSet
import fr.rowlaxx.springkutils.array.DoubleDoubleEntangledArray.Companion.deserialize
import fr.rowlaxx.springkutils.array.DoubleDoubleEntangledArray.Companion.fuse
import fr.rowlaxx.springkutils.array.DoubleDoubleEntangledArray.Companion.sum
import fr.rowlaxx.springkutils.array.DoubleDoubleEntangledArray.Companion.weightedSum
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Double-level twin of [IntDoubleEntangledArray]: a parallel `(level, quantity)` pair of arrays with
 * strictly ascending levels, where *both* the level and the quantity are [Double]. Every public
 * operation and its semantics mirror [IntDoubleEntangledArray] one-for-one.
 *
 * Floating-point levels rule out the integer-only fast paths that [IntDoubleEntangledArray] and
 * [IntIntEntangledArray] use: there is no notion of "consecutive levels" (so serialization stores
 * each level explicitly, with no packet/run-length trick) and no bounded integer span to scatter
 * into (so the dense `level - minLevel` overlay/sum paths are gone and every merge runs through the
 * ordered k-way merge). The radix sort that packs a level and an index into one `Long` is likewise
 * replaced by an in-place dual-array heapsort.
 */
class DoubleDoubleEntangledArray {
    private val first: DoubleArray
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
    fun firstNonZeroLevel(): Double? {
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
    fun lastNonZeroLevel(): Double? {
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

    operator fun get(level: Double): Double {
        val idx = lowerBound(level)
        return if (idx < end && first[idx] == level) second[idx] else 0.0
    }

    private constructor(first: DoubleArray, second: DoubleArray) {
        this.first = first
        this.second = second
        this.start = 0
        this.end = first.size
    }

    private constructor(first: DoubleArray, second: DoubleArray, start: Int, end: Int) {
        this.first = first
        this.second = second
        this.start = start
        this.end = end
    }

    constructor(pairs: Collection<Pair<Double, Double>>, allowZero: Boolean=true) {
        val tempFirst = EntangledArrayUtils.DOUBLE(pairs.size)
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
     * Aggregates the entries into logarithmic buckets, summing the values that land in each bucket.
     *
     * Each entry's level `f` (a value from the [first] array) is mapped to the integer bucket
     * `(ln(f) * invLnExp).toInt()`, and the [second] values of all entries sharing a bucket are
     * summed. The result is an [IntDoubleEntangledArray] pairing every bucket with its total in
     * ascending bucket order. A bucket whose total is exactly `0.0` (values cancelling out) is
     * dropped so the result stays canonical, and an empty receiver — or one that aggregates away
     * entirely — yields [IntDoubleEntangledArray.EMPTY].
     *
     * [invLnExp] is the reciprocal of the natural log of the bucket's geometric ratio
     * (`invLnExp = 1 / ln(ratio)`), so a constant multiplicative step in `f` advances the bucket by a
     * constant amount and a larger [invLnExp] yields finer buckets. Levels are assumed strictly
     * positive, as for prices: `ln` of a non-positive level is undefined and produces a meaningless
     * bucket.
     */
    fun zip(invLnExp: Double): IntDoubleEntangledArray {
        return IntDoubleEntangledArray.zip(first, second, start, end, invLnExp)
    }

    /**
     * Scales every quantity by [multiplier], keeping all levels and entries (zeros are not dropped).
     * Returns [EMPTY] for a zero multiplier or empty vector, and `this` unchanged for a multiplier of
     * `1.0`. Levels are unchanged and [first] is immutable, so when this vector spans the whole backing
     * array the level array is shared with the result; otherwise the `[start, end)` window is copied.
     */
    operator fun times(multiplier: Double): DoubleDoubleEntangledArray {
        if (multiplier == 0.0 || size == 0) return EMPTY
        if (multiplier == 1.0) return this

        val outQuantities = DoubleArray(size)
        for (c in start until end) {
            outQuantities[c - start] = second[c] * multiplier
        }
        return DoubleDoubleEntangledArray(windowLevels(), outQuantities)
    }

    /**
     * Divides every quantity by [divisor], keeping all levels and entries. Returns [EMPTY] for an
     * empty vector and `this` unchanged for a divisor of `1.0`. Levels are unchanged and [first] is
     * immutable, so when this vector spans the whole backing array the level array is shared with the
     * result; otherwise the `[start, end)` window is copied.
     */
    operator fun div(divisor: Double): DoubleDoubleEntangledArray {
        if (size == 0) return EMPTY
        if (divisor == 1.0) return this

        val inv = 1 / divisor
        val outQuantities = DoubleArray(size)
        for (c in start until end) {
            outQuantities[c - start] = second[c] * inv
        }

        return DoubleDoubleEntangledArray(windowLevels(), outQuantities)
    }

    /**
     * The level window `[start, end)` as a standalone array, sharing the immutable [first] backing
     * array when this vector spans it whole and copying the `[start, end)` slice otherwise.
     */
    private fun windowLevels(): DoubleArray =
        if (size == first.size) first else first.copyOfRange(start, end)

    /**
     * Canonical absolute value : every quantity replaced by its magnitude, with any
     * zero entries dropped. The input is returned unchanged (same instance) when it already
     * carries no negative and no zero quantity — the common case for a freshly built array — so
     * the all-positive path costs a single scan and no allocation. An empty or all-zero array
     * yields [EMPTY]; otherwise the scratch buffers from [ArrayUtils] hold the survivors before
     * they are trimmed into the result.
     */
    fun absolute(): DoubleDoubleEntangledArray {
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

        val first = EntangledArrayUtils.DOUBLE(size)
        val second = EntangledArrayUtils.DOUBLE_2(size)
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
    fun head(n: Int): DoubleDoubleEntangledArray {
        if (n <= 0) return EMPTY
        if (n >= size) return this
        return DoubleDoubleEntangledArray(first, second, start, start + n)
    }

    /**
     * Returns a view over the last [n] entries (highest levels). No data is copied: the result
     * shares the backing arrays and only narrows the `[start, end)` window. A non-positive [n]
     * yields [EMPTY]; an [n] at or above [size] returns `this` unchanged.
     */
    fun tail(n: Int): DoubleDoubleEntangledArray {
        if (n <= 0) return EMPTY
        if (n >= size) return this
        return DoubleDoubleEntangledArray(first, second, end - n, end)
    }

    /**
     * Returns a view over the [n] middle entries, dropping the surplus entries evenly from both ends
     * (one extra from the high end when the surplus is odd). No data is copied: the result shares the
     * backing arrays and only narrows the `[start, end)` window. A non-positive [n] yields [EMPTY]; an
     * [n] at or above [size] returns `this` unchanged.
     */
    fun trim(n: Int): DoubleDoubleEntangledArray {
        if (n <= 0) return EMPTY
        if (n >= size) return this
        val newStart = start + (size - n) / 2
        return DoubleDoubleEntangledArray(first, second, newStart, newStart + n)
    }

    /**
     * Returns a view that keeps only the entries whose level is `<= [num]`, dropping every entry above
     * [num]. No data is copied: the result shares the backing arrays and only narrows the `[start, end)`
     * window. Yields [EMPTY] when no entry qualifies and returns `this` unchanged when none is dropped.
     */
    fun dropAfter(num: Double): DoubleDoubleEntangledArray {
        val newEnd = upperBound(num)
        if (newEnd <= start) return EMPTY
        if (newEnd == end) return this
        return DoubleDoubleEntangledArray(first, second, start, newEnd)
    }

    /**
     * Returns a view that keeps only the entries whose level is `< [num]`, dropping [num] and every
     * entry above it. No data is copied: the result shares the backing arrays and only narrows the
     * `[start, end)` window. Yields [EMPTY] when no entry qualifies and returns `this` unchanged when
     * none is dropped.
     */
    fun dropStrictAfter(num: Double): DoubleDoubleEntangledArray {
        val newEnd = lowerBound(num)
        if (newEnd <= start) return EMPTY
        if (newEnd == end) return this
        return DoubleDoubleEntangledArray(first, second, start, newEnd)
    }

    /**
     * Returns a view that keeps only the entries whose level is `>= [num]`, dropping every entry below
     * [num]. No data is copied: the result shares the backing arrays and only narrows the `[start, end)`
     * window. Yields [EMPTY] when no entry qualifies and returns `this` unchanged when none is dropped.
     */
    fun dropBefore(num: Double): DoubleDoubleEntangledArray {
        val newStart = lowerBound(num)
        if (newStart >= end) return EMPTY
        if (newStart == start) return this
        return DoubleDoubleEntangledArray(first, second, newStart, end)
    }

    /**
     * Returns a view that keeps only the entries whose level is `> [num]`, dropping [num] and every
     * entry below it. No data is copied: the result shares the backing arrays and only narrows the
     * `[start, end)` window. Yields [EMPTY] when no entry qualifies and returns `this` unchanged when
     * none is dropped.
     */
    fun dropStrictBefore(num: Double): DoubleDoubleEntangledArray {
        val newStart = upperBound(num)
        if (newStart >= end) return EMPTY
        if (newStart == start) return this
        return DoubleDoubleEntangledArray(first, second, newStart, end)
    }

    /** First index in `[start, end)` whose level is `>= [num]`, or [end] if none (levels are ascending). */
    private fun lowerBound(num: Double): Int {
        var lo = start
        var hi = end
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (first[mid] < num) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** First index in `[start, end)` whose level is `> [num]`, or [end] if none (levels are ascending). */
    private fun upperBound(num: Double): Int {
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
     * each entry contributes its level and quantity as two consecutive IEEE-754 doubles. Unlike the
     * integer-level variants there is no packet/run-length encoding: floating-point levels are not
     * consecutive, so every level is stored explicitly.
     */
    fun serialize(): ByteArray {
        if (size == 0) {
            return ByteBuffer.allocate(4).putInt(0).array()
        }

        val levels = first
        val quantities = second

        val buffer = ByteBuffer.allocate(4 + size * 16)
        buffer.putInt(size)
        for (i in start until end) {
            buffer.putDouble(levels[i])
            buffer.putDouble(quantities[i])
        }

        return buffer.array()
    }

    companion object {
        val EMPTY = DoubleDoubleEntangledArray(DoubleArray(0), DoubleArray(0))
        private const val INSERTION_THRESHOLD = 40

        /**
         * Reconstructs an array from the byte form produced by [serialize]. The leading four bytes
         * give the entry count; a count of `0` yields [EMPTY]. The remaining bytes are decoded as
         * `count` consecutive `(level, quantity)` double pairs. The bytes are trusted to describe a
         * canonical array (ascending levels, no duplicates, no zeros), so the result is built directly
         * with no re-sorting or fusing. A truncated or malformed input raises [IllegalArgumentException].
         */
        fun deserialize(bytes: ByteArray): DoubleDoubleEntangledArray {
            val buffer = ByteBuffer.wrap(bytes)
            val totalSize = try {
                buffer.getInt()
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid byte array: cannot read total size", e)
            }

            if (totalSize == 0) {
                return EMPTY
            }

            val levels = DoubleArray(totalSize)
            val quantities = DoubleArray(totalSize)

            try {
                for (i in 0 until totalSize) {
                    levels[i] = buffer.getDouble()
                    quantities[i] = buffer.getDouble()
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid byte array: cannot decode entries", e)
            }

            return DoubleDoubleEntangledArray(levels, quantities)
        }

        /**
         * Trims the scratch buffers down to the [out] entries written so far, yielding the [EMPTY]
         * singleton when nothing survived. Called once per operation, not per entry.
         */
        private fun result(first: DoubleArray, second: DoubleArray, out: Int): DoubleDoubleEntangledArray {
            if (out == 0) {
                return EMPTY
            }
            return DoubleDoubleEntangledArray(first.copyOf(out), second.copyOf(out))
        }

        private fun classicSort(levels: DoubleArray, quantities: DoubleArray, end: Int) {
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

        /**
         * In-place ascending heapsort of the `[0, end)` window, swapping [quantities] in lockstep with
         * [levels]. Replaces the integer variants' radix sort, which packed a level and an index into a
         * single `Long` — a trick that cannot hold a full 64-bit double level alongside its index.
         * Equal levels need no stable ordering: [fuse] sums their quantities regardless of order.
         */
        private fun heapSort(levels: DoubleArray, quantities: DoubleArray, end: Int) {
            for (i in end / 2 - 1 downTo 0) siftDown(levels, quantities, i, end)
            for (i in end - 1 downTo 1) {
                val lv = levels[0]; levels[0] = levels[i]; levels[i] = lv
                val q = quantities[0]; quantities[0] = quantities[i]; quantities[i] = q
                siftDown(levels, quantities, 0, i)
            }
        }

        private fun siftDown(levels: DoubleArray, quantities: DoubleArray, start: Int, size: Int) {
            var root = start
            while (true) {
                var child = 2 * root + 1
                if (child >= size) break
                if (child + 1 < size && levels[child] < levels[child + 1]) child++
                if (levels[root] >= levels[child]) break
                val lv = levels[root]; levels[root] = levels[child]; levels[child] = lv
                val q = quantities[root]; quantities[root] = quantities[child]; quantities[child] = q
                root = child
            }
        }

        private fun sort(levels: DoubleArray, quantities: DoubleArray, end: Int) {
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

            heapSort(levels, quantities, end)
        }

        private fun fuse(levels: DoubleArray, quantities: DoubleArray, end: Int, allowZero: Boolean): Int {
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
        fun absoluteDiff(a: DoubleDoubleEntangledArray, b: DoubleDoubleEntangledArray): DoubleDoubleEntangledArray {
            if (a.size == 0) return b.absolute()
            if (b.size == 0) return a.absolute()

            val s = a.size + b.size
            val first = EntangledArrayUtils.DOUBLE(s)
            val second = EntangledArrayUtils.DOUBLE_2(s)

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
        fun cumulativeSet(inputs: Collection<DoubleDoubleEntangledArray>, dropZeros: Boolean = true): DoubleDoubleEntangledArray {
            val initSize = inputs.size
            val arrays = Array(initSize) { EMPTY }

            var n = 0
            var s = 0

            for (array in inputs) {
                if (array.size == 0) {
                    continue
                }

                arrays[n] = array
                s += array.size
                n++
            }

            if (n == 0) {
                return EMPTY
            }
            else if (n == 1 && !dropZeros) {
                return arrays[0]
            }

            val first = EntangledArrayUtils.DOUBLE(s)
            val second = EntangledArrayUtils.DOUBLE_2(s)

            return when(n) {
                1 -> cumulativeSet1(arrays[0], first, second, dropZeros)
                2 -> cumulativeSet2(arrays[0], arrays[1], first, second, dropZeros)
                // mergeSetN's heap can't tolerate trailing EMPTY slots, so drop them when inputs were skipped.
                else -> cumulativeSetN(arrays, n, first, second, dropZeros)
            }
        }

        private fun cumulativeSet1(array: DoubleDoubleEntangledArray, first: DoubleArray, second: DoubleArray, dropZeros: Boolean): DoubleDoubleEntangledArray {
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
            a: DoubleDoubleEntangledArray, b: DoubleDoubleEntangledArray,
            first: DoubleArray, second: DoubleArray, dropZeros: Boolean,
        ): DoubleDoubleEntangledArray {
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

        /**
         * Sparse N-way overlay via a k-way min-heap of the inputs' current heads, keyed on the cached
         * front level of each array. Runs in O(s · log N). Last-writer-wins is preserved by draining
         * every head that shares the current minimum level and keeping the largest array index.
         * Requires [arrays] to be compacted (no empty entries), which [cumulativeSet] ensures.
         */
        private fun cumulativeSetN(
            arrays: Array<DoubleDoubleEntangledArray>, n: Int,
            first: DoubleArray, second: DoubleArray, dropZeros: Boolean,
        ): DoubleDoubleEntangledArray {
            val heads = IntArray(n)
            val frontLevel = DoubleArray(n)
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
        fun weightedSum(pairs: Collection<Pair<DoubleDoubleEntangledArray, Double>>): DoubleDoubleEntangledArray {
            val initSize = pairs.size
            val arrays = Array(initSize) { EMPTY }
            val multipliers = DoubleArray(initSize) { 0.0 }

            var n = 0
            var s = 0

            for ((array, multiplier) in pairs) {
                if (multiplier == 0.0 || array.size == 0) {
                    continue
                }

                arrays[n] = array
                multipliers[n] = multiplier
                n++
                s += array.size
            }

            if (n == 0) {
                return EMPTY
            }

            val first = EntangledArrayUtils.DOUBLE(s)
            val second = EntangledArrayUtils.DOUBLE_2(s)

            return when (n) {
                1 -> weightedSum1(arrays[0], multipliers[0], first, second)
                2 -> weightedSum2(arrays[0], arrays[1], multipliers[0], multipliers[1], first, second)
                else -> weightedSumN(arrays, multipliers, n, first, second)
            }
        }

        private fun weightedSum1(
            array: DoubleDoubleEntangledArray, multiplier: Double,
            scratchFirst: DoubleArray, scratchSecond: DoubleArray,
        ): DoubleDoubleEntangledArray {
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
                return DoubleDoubleEntangledArray(srcLevels, scratchSecond.copyOf(out))
            }

            return DoubleDoubleEntangledArray(scratchFirst.copyOf(out), scratchSecond.copyOf(out))
        }

        private fun weightedSum2(
            a: DoubleDoubleEntangledArray, b: DoubleDoubleEntangledArray,
            multiplierA: Double, multiplierB: Double,
            first: DoubleArray, second: DoubleArray,
        ): DoubleDoubleEntangledArray {
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

        /**
         * Sparse N-way weighted sum via a k-way min-heap of the inputs' current heads, keyed on the
         * cached front level of each array. Every head sitting on the current minimum level is drained
         * and its weighted quantity accumulated; the level is emitted unless the total is `0.0`.
         * Requires [arrays] to be compacted (no empty entries), which [weightedSum] ensures.
         */
        private fun weightedSumN(
            arrays: Array<DoubleDoubleEntangledArray>, multipliers: DoubleArray, n: Int,
            first: DoubleArray, second: DoubleArray,
        ): DoubleDoubleEntangledArray {
            val heads = IntArray(n)
            val frontLevel = DoubleArray(n)
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
                var q = 0.0
                // Drain every head sitting on this level, summing their weighted quantities.
                while (m > 0 && frontLevel[heap[0]] == level) {
                    val k = heap[0]
                    val a = arrays[k]
                    val h = a.start + heads[k]
                    q += a.second[h] * multipliers[k]
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
                if (q != 0.0) { first[out] = level; second[out] = q; out++ }
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
        fun sum(arrays: Collection<DoubleDoubleEntangledArray>): DoubleDoubleEntangledArray {
            val initSize = arrays.size
            val kept = Array(initSize) { EMPTY }

            var n = 0
            var s = 0

            for (array in arrays) {
                if (array.size == 0) {
                    continue
                }

                kept[n] = array
                n++
                s += array.size
            }

            if (n == 0) {
                return EMPTY
            }

            val first = EntangledArrayUtils.DOUBLE(s)
            val second = EntangledArrayUtils.DOUBLE_2(s)

            return when (n) {
                1 -> sum1(kept[0], first, second)
                2 -> sum2(kept[0], kept[1], first, second)
                else -> sumN(kept, n, first, second)
            }
        }

        private fun sum1(
            array: DoubleDoubleEntangledArray,
            scratchFirst: DoubleArray, scratchSecond: DoubleArray,
        ): DoubleDoubleEntangledArray {
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

            return DoubleDoubleEntangledArray(scratchFirst.copyOf(out), scratchSecond.copyOf(out))
        }

        private fun sum2(
            a: DoubleDoubleEntangledArray, b: DoubleDoubleEntangledArray,
            first: DoubleArray, second: DoubleArray,
        ): DoubleDoubleEntangledArray {
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

        /**
         * Sparse N-way sum via a k-way min-heap of the inputs' current heads, keyed on the cached
         * front level of each array. Every head sitting on the current minimum level is drained and
         * its quantity accumulated; the level is emitted unless the total is `0.0`. Requires [arrays]
         * to be compacted (no empty entries), which [sum] ensures.
         */
        private fun sumN(
            arrays: Array<DoubleDoubleEntangledArray>, n: Int,
            first: DoubleArray, second: DoubleArray,
        ): DoubleDoubleEntangledArray {
            val heads = IntArray(n)
            val frontLevel = DoubleArray(n)
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
                var q = 0.0
                // Drain every head sitting on this level, summing their quantities.
                while (m > 0 && frontLevel[heap[0]] == level) {
                    val k = heap[0]
                    val a = arrays[k]
                    val h = a.start + heads[k]
                    q += a.second[h]
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
                if (q != 0.0) { first[out] = level; second[out] = q; out++ }
            }

            return result(first, second, out)
        }
    }

}
