package fr.rowlaxx.springkutils.array

import fr.rowlaxx.springkutils.logging.utils.LoggerExtension.log
import java.util.*
import kotlin.collections.AbstractList

object GroupByUtils {
    private const val DEFAULT_INITIAL_SIZE = 64
    private const val MAX_CACHED_ELEMENTS = 4 * 1024 * 1024 / 8
    private const val MAX_POOLED_VIEWS = 1 shl 16

    private val EMPTY_LONGS = LongArray(0)
    private val EMPTY_VALUES = arrayOfNulls<Any?>(0)

    fun <T> grouper(initialSize: Int = DEFAULT_INITIAL_SIZE): Grouper<T> = Grouper(initialSize)

    class Grouper<T> internal constructor(private val initialSize: Int) {
        private val groupings: ThreadLocal<GroupedBy<T>> = ThreadLocal.withInitial {
            ArrayUtils.warnIfShared()
            GroupedBy(initialSize)
        }

        fun groupBy(list: List<T>, key: (T) -> Long): GroupedBy<T> {
            return fill(list.size, { list[it] }, key)
        }

        @Suppress("UNCHECKED_CAST")
        fun groupBy(array: Array<out Any?>, start: Int, end: Int, key: (T) -> Long): GroupedBy<T> {
            Objects.checkFromToIndex(start, end, array.size)
            return fill(end - start, { array[start + it] as T }, key)
        }

        private inline fun fill(n: Int, element: (Int) -> T, key: (T) -> Long): GroupedBy<T> {
            val grouping = groupings.get()
            grouping.open(n)
            try {
                val keys = grouping.keys
                val values = grouping.values
                var min = Long.MAX_VALUE
                var max = Long.MIN_VALUE
                var previous = Long.MIN_VALUE
                var sorted = true
                for (i in 0 until n) {
                    val value = element(i)
                    val k = key(value)
                    values[i] = value
                    keys[i] = k
                    if (k < min) min = k
                    if (k > max) max = k
                    if (k < previous) sorted = false
                    previous = k
                }
                return grouping.group(n, min, max, sorted)
            } catch (t: Throwable) {
                grouping.abort(n)
                throw t
            }
        }
    }

    class GroupedBy<V> internal constructor(private val initialSize: Int) : AutoCloseable {
        private val owner: Thread = Thread.currentThread()
        internal var keys = LongArray(initialSize)
        internal var values = arrayOfNulls<Any?>(initialSize)
        private var cumEnd = IntArray(initialSize)
        private var tmpKeys = EMPTY_LONGS
        private var tmpValues = EMPTY_VALUES
        private var views = EMPTY_VALUES

        private var size = 0
        private var keyCount = 0
        private var open = false
        private var generation = 0

        internal fun open(n: Int) {
            check(!open) { "A grouping of this grouper is already open on ${owner.name}: close it first" }
            if (n > keys.size) grow(n)
            open = true
            generation++
        }

        private fun grow(n: Int) {
            val capacity = if (n > MAX_CACHED_ELEMENTS) {
                log.warn("Grouping {} elements, above the {} cached; served one-shot", n, MAX_CACHED_ELEMENTS)
                n
            } else {
                minOf(maxOf(n, keys.size * 2), MAX_CACHED_ELEMENTS)
            }
            keys = LongArray(capacity)
            values = arrayOfNulls(capacity)
            cumEnd = IntArray(capacity)
            tmpKeys = EMPTY_LONGS
            tmpValues = EMPTY_VALUES
        }

        internal fun group(n: Int, min: Long, max: Long, sorted: Boolean): GroupedBy<V> {
            size = n
            keyCount = when {
                n == 0 -> 0
                min == max -> {
                    keys[0] = min
                    cumEnd[0] = n
                    1
                }
                sorted -> linearGroup(n)
                else -> {
                    // `max - min` can overflow to a negative span: that must fall through to sparse.
                    val span = max - min
                    if (span in 0 until n) denseGroup(min, (span + 1).toInt(), n) else sparseGroup(n)
                }
            }
            return this
        }

        internal fun abort(n: Int) {
            values.fill(null, 0, minOf(n, values.size))
            size = 0
            release()
        }

        /** Compacts the distinct keys over the front of [keys]: every write lands below the read cursor. */
        private fun linearGroup(n: Int): Int {
            val keys = keys
            val cumEnd = cumEnd
            var g = 0
            var prev = keys[0]
            for (i in 1 until n) {
                val k = keys[i]
                if (k != prev) {
                    keys[g] = prev
                    cumEnd[g] = i
                    g++
                    prev = k
                }
            }
            keys[g] = prev
            cumEnd[g] = n
            return g + 1
        }

        /** Stable counting sort over a key range no wider than [n]; scatters into the spare buffer, then swaps. */
        private fun denseGroup(minKey: Long, range: Int, n: Int): Int {
            val keys = keys
            val cumEnd = cumEnd
            val source = values
            if (tmpValues.size < n) tmpValues = arrayOfNulls(source.size)
            val target = tmpValues

            cumEnd.fill(0, 0, range)
            for (i in 0 until n) cumEnd[(keys[i] - minKey).toInt()]++

            var offset = 0
            for (b in 0 until range) {
                val count = cumEnd[b]
                cumEnd[b] = offset
                offset += count
            }

            for (i in 0 until n) target[cumEnd[(keys[i] - minKey).toInt()]++] = source[i]

            values = target
            tmpValues = source
            source.fill(null, 0, n)

            var g = 0
            var prevEnd = 0
            for (b in 0 until range) {
                val end = cumEnd[b]
                if (end > prevEnd) {
                    keys[g] = minKey + b
                    cumEnd[g] = end
                    g++
                    prevEnd = end
                }
            }
            return g
        }

        private fun sparseGroup(n: Int): Int {
            if (tmpKeys.size < n) tmpKeys = LongArray(keys.size)
            if (tmpValues.size < n) tmpValues = arrayOfNulls(values.size)
            sortByKey(n)

            val keys = keys
            val cumEnd = cumEnd
            var g = 0
            var prev = keys[0]
            for (i in 1 until n) {
                val k = keys[i]
                if (k != prev) {
                    cumEnd[g] = i
                    g++
                    keys[g] = k
                    prev = k
                }
            }
            cumEnd[g] = n
            return g + 1
        }

        /**
         * Stable bottom-up merge sort of `(keys, values)` over `[0, n)`, ping-ponging with the tmp
         * buffers. Stability: the right run only wins on a strictly smaller key.
         */
        private fun sortByKey(n: Int) {
            var srcKeys = keys
            var srcValues = values
            var dstKeys = tmpKeys
            var dstValues = tmpValues

            var width = 1
            while (width < n) {
                var i = 0
                while (i < n) {
                    val mid = minOf(i + width, n)
                    val end = minOf(i + 2 * width, n)
                    var l = i
                    var r = mid
                    var o = i

                    while (l < mid && r < end) {
                        if (srcKeys[r] < srcKeys[l]) {
                            dstKeys[o] = srcKeys[r]; dstValues[o] = srcValues[r]; r++
                        } else {
                            dstKeys[o] = srcKeys[l]; dstValues[o] = srcValues[l]; l++
                        }
                        o++
                    }
                    while (l < mid) { dstKeys[o] = srcKeys[l]; dstValues[o] = srcValues[l]; l++; o++ }
                    while (r < end) { dstKeys[o] = srcKeys[r]; dstValues[o] = srcValues[r]; r++; o++ }

                    i = end
                }

                val swapKeys = srcKeys; srcKeys = dstKeys; dstKeys = swapKeys
                val swapValues = srcValues; srcValues = dstValues; dstValues = swapValues
                width = width shl 1
            }

            if (srcKeys !== keys) {
                System.arraycopy(srcKeys, 0, keys, 0, n)
                System.arraycopy(srcValues, 0, values, 0, n)
            }
            tmpValues.fill(null, 0, n)
        }

        private fun checkOpen() {
            if (!open) throw IllegalStateException("Grouping used after close")
        }

        val count: Int
            get() {
                checkOpen()
                return keyCount
            }

        val firstKey: Long
            get() {
                checkOpen()
                if (keyCount == 0) throw NoSuchElementException()
                return keys[0]
            }

        val lastKey: Long
            get() {
                checkOpen()
                if (keyCount == 0) throw NoSuchElementException()
                return keys[keyCount - 1]
            }

        fun keyAt(index: Int): Long {
            checkOpen()
            Objects.checkIndex(index, keyCount)
            return keys[index]
        }

        fun groupAt(index: Int): List<V> {
            checkOpen()
            Objects.checkIndex(index, keyCount)
            return view(index)
        }

        fun first(): List<V> = if (count == 0) emptyList() else view(0)

        fun last(): List<V> = if (count == 0) emptyList() else view(keyCount - 1)

        fun contains(key: Long): Boolean {
            checkOpen()
            return keys.binarySearch(key, 0, keyCount) >= 0
        }

        operator fun get(key: Long): List<V> {
            checkOpen()
            val index = keys.binarySearch(key, 0, keyCount)
            return if (index < 0) emptyList() else view(index)
        }

        inline fun forEach(action: (key: Long, group: List<V>) -> Unit) {
            for (i in 0 until count) action(keyAt(i), groupAt(i))
        }

        inline fun forEachKey(action: (key: Long) -> Unit) {
            for (i in 0 until count) action(keyAt(i))
        }

        @Suppress("UNCHECKED_CAST")
        private fun view(index: Int): List<V> {
            if (index >= MAX_POOLED_VIEWS) return View().bind(index)
            if (index >= views.size) {
                views = views.copyOf(minOf(maxOf(index + 1, views.size * 2, 8), MAX_POOLED_VIEWS))
            }
            val view = (views[index] ?: View().also { views[index] = it }) as GroupedBy<V>.View
            return if (view.generation == generation) view else view.bind(index)
        }

        override fun close() {
            if (!open) return
            check(Thread.currentThread() === owner) {
                "Grouping opened on ${owner.name} closed from ${Thread.currentThread().name}"
            }
            values.fill(null, 0, size)
            size = 0
            keyCount = 0
            release()
        }

        private fun release() {
            open = false
            generation++
            if (keys.size > MAX_CACHED_ELEMENTS) {
                keys = LongArray(initialSize)
                values = arrayOfNulls(initialSize)
                cumEnd = IntArray(initialSize)
                tmpKeys = EMPTY_LONGS
                tmpValues = EMPTY_VALUES
            }
        }

        private inner class View : AbstractList<V>(), RandomAccess {
            var generation = 0
            private var start = 0
            private var end = 0

            fun bind(index: Int): View {
                start = if (index == 0) 0 else cumEnd[index - 1]
                end = cumEnd[index]
                generation = this@GroupedBy.generation
                return this
            }

            private fun checkValid() {
                if (generation != this@GroupedBy.generation) throw IllegalStateException("Group used after close")
            }

            override val size: Int
                get() {
                    checkValid()
                    return end - start
                }

            @Suppress("UNCHECKED_CAST")
            override fun get(index: Int): V {
                checkValid()
                Objects.checkIndex(index, end - start)
                return values[start + index] as V
            }
        }
    }
}
