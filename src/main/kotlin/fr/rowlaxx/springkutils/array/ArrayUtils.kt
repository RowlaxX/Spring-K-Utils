package fr.rowlaxx.springkutils.array

import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import java.util.*
import java.util.concurrent.ForkJoinWorkerThread

object ArrayUtils {
    enum class Sorted { ASC, DESC, NONE }

    fun IntArray.sortedDirection(start: Int = 0, end: Int = size): Sorted {
        if (end - start < 2) return Sorted.ASC

        return if (this[start] <= this[start + 1]) {
            for (i in start + 2 until end) if (this[i - 1] > this[i]) return Sorted.NONE
            Sorted.ASC
        }
        else {
            for (i in start + 2 until end) if (this[i - 1] < this[i]) return Sorted.NONE
            Sorted.DESC
        }
    }

    fun DoubleArray.sortedDirection(start: Int = 0, end: Int = size): Sorted {
        if (end - start < 2) return Sorted.ASC

        return if (this[start] <= this[start + 1]) {
            for (i in start + 2 until end) if (this[i - 1] > this[i]) return Sorted.NONE
            Sorted.ASC
        }
        else {
            for (i in start + 2 until end) if (this[i - 1] < this[i]) return Sorted.NONE
            Sorted.DESC
        }
    }

    fun LongArray.sortedDirection(start: Int = 0, end: Int = size): Sorted {
        if (end - start < 2) return Sorted.ASC

        return if (this[start] <= this[start + 1]) {
            for (i in start + 2 until end) if (this[i - 1] > this[i]) return Sorted.NONE
            Sorted.ASC
        }
        else {
            for (i in start + 2 until end) if (this[i - 1] < this[i]) return Sorted.NONE
            Sorted.DESC
        }
    }
    
    internal fun warnIfShared() {
        if (!Thread.currentThread().name.startsWith(GlobalThreadConfiguration.ASYNC_THREAD_NAME)) {
            log.warn("Potential memory leak detected. Scratch arrays must be requested from the async pool.")
        }
    }

    private const val MAX_CACHED_SCRATCH_BYTES = 2L * 1024 * 1024
    private const val OBJECT_REF_BYTES = 4

    /**
     * True when an [elements]-element request is too large to cache in the thread-local: it is then
     * served as a one-shot array, so a transient spike never pins more than
     * [MAX_CACHED_SCRATCH_BYTES] per factory per thread.
     */
    private fun tooLargeToCache(elements: Int, bytesPerElement: Int, type: String): Boolean {
        val bytes = elements.toLong() * bytesPerElement
        if (bytes <= MAX_CACHED_SCRATCH_BYTES) return false
        log.warn("Scratch {} array taking too much - {} - {}MB - {}x more",
            type, elements, bytes / (1024 * 1024), bytes / MAX_CACHED_SCRATCH_BYTES,
        )
        return true
    }

    class ScratchIntArrayFactory(initialSize: Int) {
        private val scratch = ThreadLocal.withInitial {
            warnIfShared();
            IntArray(initialSize)
        }

        operator fun invoke(minSize: Int): IntArray {
            if (tooLargeToCache(minSize, Int.SIZE_BYTES, "Int")) return IntArray(minSize)
            var array = scratch.get()
            if (array.size < minSize) {
                array = IntArray(minSize)
                scratch.set(array)
            }
            return array
        }
    }

    class ScratchBooleanArrayFactory(initialSize: Int) {
        private val scratch = ThreadLocal.withInitial {
            warnIfShared();
            BooleanArray(initialSize)
        }

        operator fun invoke(minSize: Int): BooleanArray {
            if (tooLargeToCache(minSize, 1, "Boolean")) return BooleanArray(minSize)
            var array = scratch.get()
            if (array.size < minSize) {
                array = BooleanArray(minSize)
                scratch.set(array)
            }
            return array
        }
    }

    class ScratchLongArrayFactory(initialSize: Int) {
        private val scratch = ThreadLocal.withInitial {
            warnIfShared()
            LongArray(initialSize)
        }

        operator fun invoke(minSize: Int): LongArray {
            if (tooLargeToCache(minSize, Long.SIZE_BYTES, "Long")) return LongArray(minSize)
            var array = scratch.get()
            if (array.size < minSize) {
                array = LongArray(minSize)
                scratch.set(array)
            }
            return array
        }
    }

    class ScratchDoubleArrayFactory(initialSize: Int) {
        private val scratch = ThreadLocal.withInitial {
            warnIfShared()
            DoubleArray(initialSize)
        }

        operator fun invoke(minSize: Int): DoubleArray {
            if (tooLargeToCache(minSize, Double.SIZE_BYTES, "Double")) return DoubleArray(minSize)
            var array = scratch.get()
            if (array.size < minSize) {
                array = DoubleArray(minSize)
                scratch.set(array)
            }
            return array
        }
    }

    class ScratchFloatArrayFactory(initialSize: Int) {
        private val scratch = ThreadLocal.withInitial {
            warnIfShared()
            FloatArray(initialSize)
        }

        operator fun invoke(minSize: Int): FloatArray {
            if (tooLargeToCache(minSize, Float.SIZE_BYTES, "Float")) return FloatArray(minSize)
            var array = scratch.get()
            if (array.size < minSize) {
                array = FloatArray(minSize)
                scratch.set(array)
            }
            return array
        }
    }

    class ScratchShortArrayFactory(initialSize: Int) {
        private val scratch = ThreadLocal.withInitial {
            warnIfShared()
            ShortArray(initialSize)
        }

        operator fun invoke(minSize: Int): ShortArray {
            if (tooLargeToCache(minSize, Short.SIZE_BYTES, "Short")) return ShortArray(minSize)
            var array = scratch.get()
            if (array.size < minSize) {
                array = ShortArray(minSize)
                scratch.set(array)
            }
            return array
        }
    }

    class ScratchByteArrayFactory(initialSize: Int) {
        private val scratch = ThreadLocal.withInitial {
            warnIfShared()
            ByteArray(initialSize)
        }

        operator fun invoke(minSize: Int): ByteArray {
            if (tooLargeToCache(minSize, 1, "Byte")) return ByteArray(minSize)
            var array = scratch.get()
            if (array.size < minSize) {
                array = ByteArray(minSize)
                scratch.set(array)
            }
            return array
        }
    }

    class ScratchCharArrayFactory(initialSize: Int) {
        private val scratch = ThreadLocal.withInitial {
            warnIfShared()
            CharArray(initialSize)
        }

        operator fun invoke(minSize: Int): CharArray {
            if (tooLargeToCache(minSize, Char.SIZE_BYTES, "Char")) return CharArray(minSize)
            var array = scratch.get()
            if (array.size < minSize) {
                array = CharArray(minSize)
                scratch.set(array)
            }
            return array
        }
    }

    class ScratchArrayFactory(initialSize: Int) {
        private val scratch = ThreadLocal.withInitial {
            warnIfShared()
            arrayOfNulls<Any?>(initialSize)
        }

        @Suppress("UNCHECKED_CAST")
        operator fun invoke(minSize: Int): Array<Any?> {
            if (tooLargeToCache(minSize, OBJECT_REF_BYTES, "Object")) return arrayOfNulls(minSize)
            var array = scratch.get()
            if (array.size < minSize) {
                array = arrayOfNulls<Any?>(minSize)
                scratch.set(array)
            }
            return array
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmName("drainAny")
    fun <T> Array<Any?>.drain(n: Int): List<T> {
        val out = slice(0 until n) as List<T>
        fill(null, 0, n)
        return out
    }

    fun Array<Any?>.clear(n: Int) {
        fill(null, 0, n)
    }

    fun <T> Array<Any?>.asList(start: Int, end: Int): List<T> {
        Objects.checkFromToIndex(start, end, size)
        return RangeViewList<T>(this, start, end)
    }

    fun LongArray.drain(n: Int): List<Long> = slice(0 until n)
    fun IntArray.drain(n: Int): List<Int> = slice(0 until n)
    fun DoubleArray.drain(n: Int): List<Double> = slice(0 until n)
    fun FloatArray.drain(n: Int): List<Float> = slice(0 until n)
    fun ShortArray.drain(n: Int): List<Short> = slice(0 until n)
    fun ByteArray.drain(n: Int): List<Byte> = slice(0 until n)
    fun CharArray.drain(n: Int): List<Char> = slice(0 until n)
    fun BooleanArray.drain(n: Int): List<Boolean> = slice(0 until n)

    private class RangeViewList<V>(
        private val array: Array<Any?>,
        private val start: Int,
        private val end: Int
    ) : AbstractList<V>() {
        init {
            Objects.checkFromToIndex(start, end, array.size)
        }

        override val size: Int get() = end - start

        @Suppress("UNCHECKED_CAST")
        override fun get(index: Int): V {
            Objects.checkIndex(index, size)
            return array[start + index] as V
        }
    }

    class GroupedBy<V>(
        private val keys: LongArray,
        private val cumEnd: IntArray,
        private val values: Array<Any?>,
        private val keyCount: Int,
    ) : AutoCloseable {

        fun first(): List<V> {
            if (keyCount == 0) {
                return emptyList()
            }

            return RangeViewList(values, 0, cumEnd[0])
        }

        fun last(): List<V> {
            if (keyCount == 0) {
                return emptyList()
            }

            val start = if (keyCount == 1) 0 else cumEnd[keyCount - 2]
            val end = cumEnd[keyCount - 1]

            return RangeViewList(values, start, end)
        }

        fun contains(key: Long): Boolean {
            return keys.binarySearch(key, 0, keyCount) >= 0
        }

        operator fun get(key: Long): List<V> {
            val index = keys.binarySearch(key, 0, keyCount)

            if (index < 0) {
                return emptyList()
            }


            val start = if (index == 0) 0 else cumEnd[index - 1]
            val end = cumEnd[index]

            return RangeViewList(values, start, end)
        }

        fun forEach(action: (Long, List<V>) -> Unit) {
            for (i in 0 until keyCount) {
                val key = keys[i]
                val start = if (i == 0) 0 else cumEnd[i - 1]
                val end = cumEnd[i]
                val list = RangeViewList<V>(values, start, end)

                action(key, list)
            }
        }

        fun forEachKey(action: (Long) -> Unit) {
            for (i in 0 until keyCount) {
                action(keys[i])
            }
        }

        override fun close() {
            if (keyCount == 0) {
                return
            }
            values.fill(null, 0, cumEnd[keyCount - 1])
        }

        val count get() = keyCount

    }

    private const val GROUP_SCRATCH_SIZE = 64
    private val GROUP_KEYS = ScratchLongArrayFactory(GROUP_SCRATCH_SIZE)
    private val GROUP_CUM_END = ScratchIntArrayFactory(GROUP_SCRATCH_SIZE)
    private val GROUP_VALUES = ScratchArrayFactory(GROUP_SCRATCH_SIZE)

    @Suppress("UNCHECKED_CAST")
    fun <T> List<T>.unsafeGroupBy(key: (T) -> Long): GroupedBy<T> {
        val n = size

        if (n == 0) {
            return GroupedBy(GROUP_KEYS(0), GROUP_CUM_END(0), GROUP_VALUES(0), 0)
        }
        if (n == 1) {
            val groupKeys = GROUP_KEYS(1)
            val groupCumEnd = GROUP_CUM_END(1)
            val groupValues = GROUP_VALUES(1)
            groupKeys[0] = key(this[0])
            groupCumEnd[0] = 1
            groupValues[0] = this[0]
            return GroupedBy(groupKeys, groupCumEnd, groupValues, 1)
        }

        var minKey = Long.MAX_VALUE
        var maxKey = Long.MIN_VALUE

        for (i in 0 until n) {
            val k = key(this[i])
            if (k < minKey) minKey = k
            if (k > maxKey) maxKey = k
        }

        if (minKey == maxKey) {
            val groupValues = GROUP_VALUES(n)
            val groupKeys = GROUP_KEYS(1)
            val groupCumEnd = GROUP_CUM_END(1)
            groupKeys[0] = minKey
            groupCumEnd[0] = n

            for (i in 0 until n) {
                groupValues[i] = this[i]
            }

            return GroupedBy(groupKeys, groupCumEnd, groupValues, 1)
        }

        val group = GROUP_VALUES(n)
        val groupKeys = GROUP_KEYS(n)
        val groupCumEnd = GROUP_CUM_END(n)

        val span = maxKey - minKey
        val keyCount = if (span in 0 until n) {
            denseGroup(this, key, group, groupKeys, groupCumEnd, minKey, (span + 1).toInt(), n)
        }
        else {
            sparseGroup(this, key, group, groupKeys, groupCumEnd, n)
        }

        return GroupedBy(groupKeys, groupCumEnd, group, keyCount)
    }

    private fun <T> denseGroup(
        source: List<T>, key: (T) -> Long,
        group: Array<Any?>, groupKeys: LongArray, groupCumEnd: IntArray,
        minKey: Long, range: Int, n: Int,
    ): Int {
        for (b in 0 until range) groupCumEnd[b] = 0
        for (i in 0 until n) groupCumEnd[(key(source[i]) - minKey).toInt()]++

        var offset = 0
        for (b in 0 until range) {
            val count = groupCumEnd[b]
            groupCumEnd[b] = offset
            offset += count
        }

        for (i in 0 until n) {
            val item = source[i]
            group[groupCumEnd[(key(item) - minKey).toInt()]++] = item
        }

        var g = 0
        var prevEnd = 0
        for (b in 0 until range) {
            val end = groupCumEnd[b]
            if (end > prevEnd) {
                groupKeys[g] = minKey + b
                groupCumEnd[g] = end
                g++
                prevEnd = end
            }
        }
        return g
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> sparseGroup(
        source: List<T>, key: (T) -> Long,
        group: Array<Any?>, groupKeys: LongArray, groupCumEnd: IntArray, n: Int,
    ): Int {
        for (i in 0 until n) group[i] = source[i]
        Arrays.sort(group, 0, n, { a, b -> key(a as T).compareTo(key(b as T)) })

        var g = 0
        var prev = key(group[0] as T)
        groupKeys[0] = prev
        for (i in 1 until n) {
            val k = key(group[i] as T)
            if (k != prev) {
                groupCumEnd[g] = i
                g++
                groupKeys[g] = k
                prev = k
            }
        }

        groupCumEnd[g] = n
        return g + 1
    }
}


