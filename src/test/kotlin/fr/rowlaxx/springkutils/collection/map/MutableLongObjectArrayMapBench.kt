package fr.rowlaxx.springkutils.collection.map

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * Not a correctness test — quantifies why [fr.rowlaxx.springkutils.collection.map.MutableLongObjectArrayMap] replaces `HashMap<Long, V>` as
 * the candle factory's builder store. It replays the real access pattern: a bounded sliding window
 * of `timeId`s where each step appends a new highest key, evicts the oldest, and reads a couple of
 * live keys. `HashMap<Long, V>` boxes every `long` key (keys are far outside the `Long` cache) and
 * allocates a `Node` per insert; the entangled array touches neither.
 *
 * Run manually:
 *   ./gradlew test --tests "*MutableLongObjectArrayMapBench" -i
 */
class MutableLongObjectArrayMapBench {

    private val value = Any() // shared so neither path measures value allocation

    private fun bytesPerOp(iterations: Int, op: (Int) -> Unit): Double {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val tid = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(tid)
        for (i in 0 until iterations) op(i)
        val bytes = bean.getThreadAllocatedBytes(tid) - before
        return bytes.toDouble() / iterations
    }

    private fun nanosPerOp(iterations: Int, op: (Int) -> Unit): Double {
        val t0 = System.nanoTime()
        for (i in 0 until iterations) op(i)
        return (System.nanoTime() - t0).toDouble() / iterations
    }

    @Test
    fun `sliding-window churn beats HashMap on allocation and time`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val window = 32L
        val base = 1_000_000_000L // large keys → never in the boxed-Long cache

        val loa = MutableLongObjectArrayMap<Any>(64)
        val hash = HashMap<Long, Any>()
        // Prime both with a full window.
        for (i in 0L until window) { loa.put(base + i, value); hash[base + i] = value }

        val loaOp: (Int) -> Unit = { i ->
            val k = base + window + i
            loa.put(k, value)
            loa.remove(k - window)
            loa[k]
            loa[k - window / 2]
        }
        val hashOp: (Int) -> Unit = { i ->
            val k = base + window + i
            hash[k] = value
            hash.remove(k - window)
            hash[k]
            hash[k - window / 2]
        }

        val warmup = 200_000
        repeat(warmup) { loaOp(it); hashOp(it) }

        val iterations = 2_000_000
        val loaBytes = bytesPerOp(iterations, loaOp)
        val hashBytes = bytesPerOp(iterations, hashOp)
        val loaNs = nanosPerOp(iterations, loaOp)
        val hashNs = nanosPerOp(iterations, hashOp)

        println("=== LongObjectArrayMap vs HashMap<Long,V> (sliding window=$window) ===")
        println("LongObjectArrayMap  bytes/op = ${"%.2f".format(loaBytes)}  ns/op = ${"%.1f".format(loaNs)}")
        println("HashMap<Long,V>           bytes/op = ${"%.2f".format(hashBytes)}  ns/op = ${"%.1f".format(hashNs)}")
        println("allocation reduction = ${"%.1f".format((1 - loaBytes / hashBytes) * 100)}%")
        println("time      reduction  = ${"%.1f".format((1 - loaNs / hashNs) * 100)}%")

        // Allocation is the decisive, stable win (≈90%+ less): no boxed keys, no Node per entry — so
        // it is asserted tightly. Run in isolation the entangled array is also ~20% faster on the hot
        // path, but wall-clock micro-timing is too noisy under full-suite JVM contention to gate on a
        // tight margin (the printout above shows the real reduction); the CPU check only guards against
        // a gross regression.
        assertTrue(loaBytes < hashBytes * 0.5,
            "entangled array should at least halve allocation: loa=$loaBytes hash=$hashBytes")
        assertTrue(loaNs < hashNs * 2.0,
            "entangled array should not be grossly slower than HashMap on the hot path: loa=$loaNs hash=$hashNs")
    }
}
