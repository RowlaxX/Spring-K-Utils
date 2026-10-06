package fr.rowlaxx.springkutils.array

import fr.rowlaxx.springkutils.array.ArrayUtils.MAX_CACHED_SCRATCH_BYTES
import fr.rowlaxx.springkutils.concurrent.config.GlobalThreadConfiguration
import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import java.util.*

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

    private const val MAX_CACHED_SCRATCH_BYTES = 4L * 1024 * 1024
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
        if (n == 0) return emptyList()

        val out = copyOfRange(0, n).asList() as List<T>
        fill(null, 0, n)
        return out
    }

    fun Array<Any?>.clear(n: Int) {
        fill(null, 0, n)
    }

    fun <T> Array<Any?>.asList(start: Int, end: Int): List<T> {
        Objects.checkFromToIndex(start, end, size)
        if (start == end) return emptyList()
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
}
