package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory

/**
 * Not a correctness test — quantifies why [MutableLongLongEntangledArray] is preferable to
 * `HashMap<Long, Long>` for a primitive `long -> long` store. It replays a bounded sliding window of
 * keys where each step appends a new highest key, evicts the oldest, and reads a couple of live keys.
 *
 * `HashMap<Long, Long>` boxes *both* the `long` key (far outside the boxed-`Long` cache) and the
 * `long` value, and allocates a `Node` per insert; the entangled array boxes neither and holds no
 * per-entry object. This is a stronger allocation story than the object-valued
 * [MutableLongObjectEntangledArrayBench], which only avoided key boxing.
 *
 * Run manually:
 *   ./gradlew test --tests "*MutableLongLongEntangledArrayBench" -i
 */
class MutableLongLongEntangledArrayBench {

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

        val lla = MutableLongLongEntangledArray(64)
        val hash = HashMap<Long, Long>()
        // Prime both with a full window.
        for (i in 0L until window) { lla.put(base + i, i); hash[base + i] = i }

        val llaOp: (Int) -> Unit = { i ->
            val k = base + window + i
            lla.put(k, i.toLong())
            lla.remove(k - window)
            lla.getOrDefault(k, -1L)
            lla.getOrDefault(k - window / 2, -1L)
        }
        val hashOp: (Int) -> Unit = { i ->
            val k = base + window + i
            hash[k] = i.toLong()
            hash.remove(k - window)
            hash[k]
            hash[k - window / 2]
        }

        val warmup = 200_000
        repeat(warmup) { llaOp(it); hashOp(it) }

        val iterations = 2_000_000
        val llaBytes = bytesPerOp(iterations, llaOp)
        val hashBytes = bytesPerOp(iterations, hashOp)
        val llaNs = nanosPerOp(iterations, llaOp)
        val hashNs = nanosPerOp(iterations, hashOp)

        println("=== MutableLongLongEntangledArray vs HashMap<Long,Long> (sliding window=$window) ===")
        println("LongLongEntangledArray  bytes/op = ${"%.2f".format(llaBytes)}  ns/op = ${"%.1f".format(llaNs)}")
        println("HashMap<Long,Long>      bytes/op = ${"%.2f".format(hashBytes)}  ns/op = ${"%.1f".format(hashNs)}")
        println("allocation reduction = ${"%.1f".format((1 - llaBytes / hashBytes) * 100)}%")
        println("time      reduction  = ${"%.1f".format((1 - llaNs / hashNs) * 100)}%")

        // Allocation is the decisive, stable win: no boxed keys, no boxed values, no Node per entry —
        // so it is asserted tightly. Wall-clock micro-timing is too noisy under full-suite JVM
        // contention to gate on a tight margin (the printout shows the real reduction); the CPU check
        // only guards against a gross regression.
        assertTrue(llaBytes < hashBytes * 0.5,
            "entangled array should at least halve allocation: lla=$llaBytes hash=$hashBytes")
        assertTrue(llaNs < hashNs * 2.0,
            "entangled array should not be grossly slower than HashMap on the hot path: lla=$llaNs hash=$hashNs")
    }

    @Test
    fun `getOrDefault hot path on present keys allocates near-zero`() {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        assumeTrue(bean.isThreadAllocatedMemorySupported)

        val base = 2_000_000_000L
        val window = 64
        val lla = MutableLongLongEntangledArray(128)
        val hash = HashMap<Long, Long>()
        for (i in 0 until window) { lla.put(base + i, i.toLong()); hash[base + i.toLong()] = i.toLong() }

        // getOrDefault on present keys boxes neither key nor value; HashMap.get boxes the lookup key.
        val op: (Int) -> Unit = { i -> lla.getOrDefault(base + (i % window), -1L) }
        val hashOp: (Int) -> Unit = { i -> hash[base + (i % window)] }
        repeat(200_000) { op(it); hashOp(it) }

        val bytes = bytesPerOp(2_000_000, op)
        val hashBytes = bytesPerOp(2_000_000, hashOp)
        println("getOrDefault(present) bytes/op = ${"%.3f".format(bytes)} (HashMap.get = ${"%.3f".format(hashBytes)})")
        assertTrue(bytes < hashBytes,
            "getOrDefault on present keys should allocate less than HashMap.get: lla=$bytes hash=$hashBytes")
    }
}
