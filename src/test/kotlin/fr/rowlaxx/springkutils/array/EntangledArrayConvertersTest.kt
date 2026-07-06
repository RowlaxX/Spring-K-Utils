package fr.rowlaxx.springkutils.array

import com.sun.management.ThreadMXBean
import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.management.ManagementFactory
import kotlin.random.Random

/**
 * Test suite for the three JPA [AttributeConverter] implementations in this package:
 * [IntIntEntangledArrayConverter], [IntDoubleEntangledArrayConverter] and
 * [DoubleDoubleEntangledArrayConverter].
 *
 * Each converter is a thin, stateless delegate: `convertToDatabaseColumn(a) == a?.serialize()` and
 * `convertToEntityAttribute(b) == b?.let { Type.deserialize(it) }`. The tests therefore focus on:
 *
 *  - the null contract (the only real branch in each method),
 *  - faithful delegation (same bytes as `serialize`, same instance content as `deserialize`),
 *  - full round-trip fidelity over many array shapes (built canonically, so serialize/deserialize is
 *    lossless), and
 *  - the `@Converter(autoApply = true)` annotation / `AttributeConverter` contract via reflection.
 *
 * Content equality is asserted the same way the sibling `*EntangledArrayTest` files do: there is no
 * `equals` override on these classes, so two arrays are "equal" iff their serialized bytes match (the
 * byte form is canonical for a canonical array) — cross-checked with [toLevelValueList] where useful.
 */
class EntangledArrayConvertersTest {

    // ---- shared allocation helper (mirrors LongObjectEntangledArrayBench) -------------------------

    private fun bytesPerOp(iterations: Int, op: (Int) -> Unit): Double {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        val tid = Thread.currentThread().threadId()
        val before = bean.getThreadAllocatedBytes(tid)
        for (i in 0 until iterations) op(i)
        val bytes = bean.getThreadAllocatedBytes(tid) - before
        return bytes.toDouble() / iterations
    }

    // =============================================================================================
    // IntIntEntangledArrayConverter
    // =============================================================================================
    @Nested
    inner class IntIntConverterTests {
        private val converter = IntIntEntangledArrayConverter()
        private val EMPTY = IntIntEntangledArray.EMPTY

        private fun arr(vararg pairs: Pair<Int, Int>) = IntIntEntangledArray(pairs.toList())
        private fun arr(pairs: List<Pair<Int, Int>>) = IntIntEntangledArray(pairs)

        /** Round-trips [a] through the converter and asserts byte-level (canonical) equality. */
        private fun roundTrip(a: IntIntEntangledArray) {
            val bytes = converter.convertToDatabaseColumn(a)
            assertNotNull(bytes)
            val back = converter.convertToEntityAttribute(bytes)
            assertNotNull(back)
            assertArrayEquals(a.serialize(), back!!.serialize(),
                "round-trip changed the canonical byte form")
            assertEquals(a.toLevelValueList(), back.toLevelValueList(),
                "round-trip changed the (level, value) content")
        }

        // ---- null contract ----
        @Test fun `toDatabaseColumn null is null`() {
            assertNull(converter.convertToDatabaseColumn(null))
        }

        @Test fun `toEntityAttribute null is null`() {
            assertNull(converter.convertToEntityAttribute(null))
        }

        // ---- delegation / consistency ----
        @Test fun `toDatabaseColumn equals serialize bytes`() {
            val a = arr(1 to 10, 2 to 20, 3 to 30)
            assertArrayEquals(a.serialize(), converter.convertToDatabaseColumn(a))
        }

        @Test fun `toEntityAttribute equals deserialize content`() {
            val a = arr(5 to 1, 6 to 2, 7 to 3)
            val bytes = a.serialize()
            val viaConverter = converter.convertToEntityAttribute(bytes)!!
            val viaStatic = IntIntEntangledArray.deserialize(bytes)
            assertArrayEquals(viaStatic.serialize(), viaConverter.serialize())
        }

        @Test fun `delegation is faithful for many shapes`() {
            val shapes = listOf(
                arr(),
                arr(7 to 3),
                arr(1 to 1, 2 to 2, 3 to 3),
                arr(-5 to -1, 0 to 0, 5 to 1),
            )
            for (s in shapes) {
                assertArrayEquals(s.serialize(), converter.convertToDatabaseColumn(s))
            }
        }

        // ---- empty ----
        @Test fun `empty toDatabaseColumn is non-null and round-trips to EMPTY singleton`() {
            val bytes = converter.convertToDatabaseColumn(EMPTY)
            assertNotNull(bytes)
            assertEquals(4, bytes!!.size, "empty serializes to a 4-byte zero count")
            assertSame(EMPTY, converter.convertToEntityAttribute(bytes))
        }

        @Test fun `empty round-trip`() { roundTrip(EMPTY) }

        // ---- round-trip shapes ----
        @Test fun `roundtrip single element`() { roundTrip(arr(0 to 1)) }
        @Test fun `roundtrip single negative level`() { roundTrip(arr(-42 to 7)) }
        @Test fun `roundtrip single negative quantity`() { roundTrip(arr(3 to -99)) }
        @Test fun `roundtrip ascending dense levels`() {
            roundTrip(arr((1..50).map { it to it * 2 }))
        }
        @Test fun `roundtrip ascending with gaps`() {
            roundTrip(arr(listOf(1 to 1, 10 to 2, 100 to 3, 1000 to 4)))
        }
        @Test fun `roundtrip negative levels`() {
            roundTrip(arr((-20..-1).map { it to (it * 3) }))
        }
        @Test fun `roundtrip levels spanning zero`() {
            roundTrip(arr((-10..10).filter { it != 0 }.map { it to (it + 100) }))
        }
        @Test fun `roundtrip duplicate-fused levels`() {
            // duplicates fuse (sum) at construction; converter sees the canonical fused array.
            val a = arr(5 to 1, 5 to 2, 5 to 3, 6 to 10)
            assertEquals(6, a[5], "fused quantity should be 1+2+3")
            roundTrip(a)
        }
        @Test fun `roundtrip extreme level Int MAX`() { roundTrip(arr(Int.MAX_VALUE to 1)) }
        @Test fun `roundtrip extreme level Int MIN`() { roundTrip(arr(Int.MIN_VALUE to 1)) }
        @Test fun `roundtrip extreme level both ends`() {
            roundTrip(arr(Int.MIN_VALUE to -1, 0 to 5, Int.MAX_VALUE to 1))
        }
        @Test fun `roundtrip extreme quantities`() {
            roundTrip(arr(1 to Int.MAX_VALUE, 2 to Int.MIN_VALUE, 3 to 0.let { 1 }))
        }
        @Test fun `roundtrip allowZero variant keeps zero entries`() {
            val a = IntIntEntangledArray(listOf(1 to 0, 2 to 5, 3 to 0), allowZero = true)
            assertEquals(3, a.size, "allowZero=true keeps zero entries")
            roundTrip(a)
        }
        @Test fun `roundtrip allowZero false drops zeros`() {
            val a = IntIntEntangledArray(listOf(1 to 0, 2 to 5, 3 to 0), allowZero = false)
            roundTrip(a)
        }
        @Test fun `roundtrip large dense array`() {
            roundTrip(arr((0 until 2000).map { it to (it - 1000) }))
        }
        @Test fun `roundtrip large array with gaps`() {
            val rnd = Random(1)
            val levels = sortedSetOf<Int>()
            while (levels.size < 800) levels.add(rnd.nextInt(-100_000, 100_000))
            roundTrip(arr(levels.map { it to rnd.nextInt(1, 1_000_000) }))
        }
        @Test fun `roundtrip mixed dense packets and gaps`() {
            val pairs = ArrayList<Pair<Int, Int>>()
            var lvl = 0
            repeat(20) { p ->
                repeat(30) { pairs.add(lvl++ to (p * 100 + it)) }
                lvl += 500 // gap between packets
            }
            roundTrip(arr(pairs))
        }

        // ---- annotation / contract ----
        @Test fun `is annotated Converter autoApply true`() {
            val ann = converter.javaClass.getAnnotation(Converter::class.java)
            assertNotNull(ann, "missing @Converter")
            assertTrue(ann!!.autoApply, "autoApply should be true")
        }

        @Test fun `implements AttributeConverter`() {
            assertTrue(converter is AttributeConverter<*, *>)
        }

        // ---- integration ----
        @Test fun `integration persist load cycle`() {
            // realistic orderbook-like array: dense band of levels with int quantities
            val book = arr((10_000..10_300).map { it to (1000 - (it - 10_000)) })
            val dbColumn: ByteArray? = converter.convertToDatabaseColumn(book) // "persist"
            val loaded = converter.convertToEntityAttribute(dbColumn)          // "load"
            assertNotNull(loaded)
            assertEquals(book.toLevelValueList(), loaded!!.toLevelValueList())
            assertArrayEquals(book.serialize(), loaded.serialize())
        }

        // ---- perf / memory ----
        @Test fun `perf roundtrip throughput and allocation overhead`() {
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            assumeTrue(bean.isThreadAllocatedMemorySupported)

            val a = arr((0 until 4000).map { it to (it * 7 - 13) })
            val bytes = a.serialize()

            val convOp: (Int) -> Unit = {
                val b = converter.convertToDatabaseColumn(a)!!
                converter.convertToEntityAttribute(b)
            }
            val rawOp: (Int) -> Unit = {
                val b = a.serialize()
                IntIntEntangledArray.deserialize(b)
            }

            repeat(2000) { convOp(it); rawOp(it) }
            val iterations = 20_000
            val convBytes = bytesPerOp(iterations, convOp)
            val rawBytes = bytesPerOp(iterations, rawOp)

            val t0 = System.nanoTime()
            repeat(iterations) { convOp(it) }
            val nsPerOp = (System.nanoTime() - t0).toDouble() / iterations

            println("[IntInt] convert bytes/op=${"%.0f".format(convBytes)} raw bytes/op=${"%.0f".format(rawBytes)} ns/op=${"%.0f".format(nsPerOp)} payload=${bytes.size}B")
            // The converter is a pure delegate: its allocation must be within a small constant of the
            // raw serialize+deserialize path (generous 1.25x to absorb measurement noise).
            assertTrue(convBytes <= rawBytes * 1.25 + 256,
                "converter adds gross allocation overhead: conv=$convBytes raw=$rawBytes")
        }
    }

    // =============================================================================================
    // IntDoubleEntangledArrayConverter
    // =============================================================================================
    @Nested
    inner class IntDoubleConverterTests {
        private val converter = IntDoubleEntangledArrayConverter()
        private val EMPTY = IntDoubleEntangledArray.EMPTY

        private fun arr(vararg pairs: Pair<Int, Double>) = IntDoubleEntangledArray(pairs.toList())
        private fun arr(pairs: List<Pair<Int, Double>>) = IntDoubleEntangledArray(pairs)

        private fun roundTrip(a: IntDoubleEntangledArray) {
            val bytes = converter.convertToDatabaseColumn(a)
            assertNotNull(bytes)
            val back = converter.convertToEntityAttribute(bytes)
            assertNotNull(back)
            assertArrayEquals(a.serialize(), back!!.serialize(),
                "round-trip changed the canonical byte form")
        }

        // ---- null contract ----
        @Test fun `toDatabaseColumn null is null`() {
            assertNull(converter.convertToDatabaseColumn(null))
        }

        @Test fun `toEntityAttribute null is null`() {
            assertNull(converter.convertToEntityAttribute(null))
        }

        // ---- delegation / consistency ----
        @Test fun `toDatabaseColumn equals serialize bytes`() {
            val a = arr(1 to 1.5, 2 to 2.5, 3 to 3.5)
            assertArrayEquals(a.serialize(), converter.convertToDatabaseColumn(a))
        }

        @Test fun `toEntityAttribute equals deserialize content`() {
            val a = arr(5 to 0.1, 6 to 0.2, 7 to 0.3)
            val bytes = a.serialize()
            val viaConverter = converter.convertToEntityAttribute(bytes)!!
            val viaStatic = IntDoubleEntangledArray.deserialize(bytes)
            assertArrayEquals(viaStatic.serialize(), viaConverter.serialize())
        }

        @Test fun `delegation is faithful for many shapes`() {
            val shapes = listOf(
                arr(),
                arr(7 to 3.14),
                arr(1 to 1.0, 2 to 2.0, 3 to 3.0),
                arr(-5 to -1.5, 5 to 1.5),
            )
            for (s in shapes) assertArrayEquals(s.serialize(), converter.convertToDatabaseColumn(s))
        }

        // ---- empty ----
        @Test fun `empty toDatabaseColumn is non-null and round-trips to EMPTY singleton`() {
            val bytes = converter.convertToDatabaseColumn(EMPTY)
            assertNotNull(bytes)
            assertEquals(4, bytes!!.size)
            assertSame(EMPTY, converter.convertToEntityAttribute(bytes))
        }

        @Test fun `empty round-trip`() { roundTrip(EMPTY) }

        // ---- round-trip shapes ----
        @Test fun `roundtrip single element`() { roundTrip(arr(0 to 1.0)) }
        @Test fun `roundtrip single negative level`() { roundTrip(arr(-42 to 7.25)) }
        @Test fun `roundtrip single negative quantity`() { roundTrip(arr(3 to -99.99)) }
        @Test fun `roundtrip ascending dense levels`() {
            roundTrip(arr((1..50).map { it to it * 0.5 }))
        }
        @Test fun `roundtrip ascending with gaps`() {
            roundTrip(arr(listOf(1 to 1.1, 10 to 2.2, 100 to 3.3, 1000 to 4.4)))
        }
        @Test fun `roundtrip negative levels`() {
            roundTrip(arr((-20..-1).map { it to (it * 0.3) }))
        }
        @Test fun `roundtrip levels spanning zero`() {
            roundTrip(arr((-10..10).filter { it != 0 }.map { it to (it + 0.5) }))
        }
        @Test fun `roundtrip fractional quantities`() {
            roundTrip(arr(1 to 0.123456789, 2 to 1e-12, 3 to 9.87654321e10))
        }
        @Test fun `roundtrip duplicate-fused levels`() {
            val a = arr(5 to 1.0, 5 to 2.0, 5 to 3.0, 6 to 10.0)
            assertEquals(6.0, a[5], 0.0)
            roundTrip(a)
        }
        @Test fun `roundtrip extreme level Int MAX`() { roundTrip(arr(Int.MAX_VALUE to 1.0)) }
        @Test fun `roundtrip extreme level Int MIN`() { roundTrip(arr(Int.MIN_VALUE to 1.0)) }
        @Test fun `roundtrip extreme level both ends`() {
            roundTrip(arr(Int.MIN_VALUE to -1.0, 0 to 5.0, Int.MAX_VALUE to 1.0))
        }
        @Test fun `roundtrip extreme double quantities`() {
            roundTrip(arr(1 to Double.MAX_VALUE, 2 to Double.MIN_VALUE, 3 to -Double.MAX_VALUE))
        }
        @Test fun `roundtrip special double values NaN Infinity preserved by serialize`() {
            // serialize writes raw IEEE-754 doubles, deserialize reads them back; these survive.
            val a = arr(1 to Double.NaN, 2 to Double.POSITIVE_INFINITY, 3 to Double.NEGATIVE_INFINITY)
            roundTrip(a) // byte equality covers NaN bit-pattern fidelity
            val back = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(a))!!
            assertTrue(back[1].isNaN())
            assertEquals(Double.POSITIVE_INFINITY, back[2])
            assertEquals(Double.NEGATIVE_INFINITY, back[3])
        }
        @Test fun `roundtrip negative zero quantity bit pattern preserved`() {
            // allowZero keeps the entry; serialize stores the raw bits so -0.0 survives distinctly.
            val a = IntDoubleEntangledArray(listOf(1 to -0.0, 2 to 5.0), allowZero = true)
            roundTrip(a)
            val back = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(a))!!
            assertEquals(java.lang.Double.doubleToRawLongBits(-0.0),
                java.lang.Double.doubleToRawLongBits(back[1]))
        }
        @Test fun `roundtrip allowZero keeps zero entries`() {
            val a = IntDoubleEntangledArray(listOf(1 to 0.0, 2 to 5.0, 3 to 0.0), allowZero = true)
            assertEquals(3, a.size)
            roundTrip(a)
        }
        @Test fun `roundtrip large dense array`() {
            roundTrip(arr((0 until 2000).map { it to (it - 1000) * 1.25 }))
        }
        @Test fun `roundtrip large array with gaps`() {
            val rnd = Random(2)
            val levels = sortedSetOf<Int>()
            while (levels.size < 800) levels.add(rnd.nextInt(-100_000, 100_000))
            roundTrip(arr(levels.map { it to rnd.nextDouble() * 1e6 }))
        }
        @Test fun `roundtrip mixed dense packets and gaps`() {
            val pairs = ArrayList<Pair<Int, Double>>()
            var lvl = 0
            repeat(20) { p ->
                repeat(30) { pairs.add(lvl++ to (p * 100 + it).toDouble()) }
                lvl += 500
            }
            roundTrip(arr(pairs))
        }

        // ---- annotation / contract ----
        @Test fun `is annotated Converter autoApply true`() {
            val ann = converter.javaClass.getAnnotation(Converter::class.java)
            assertNotNull(ann)
            assertTrue(ann!!.autoApply)
        }

        @Test fun `implements AttributeConverter`() {
            assertTrue(converter is AttributeConverter<*, *>)
        }

        // ---- integration ----
        @Test fun `integration persist load cycle`() {
            val book = arr((10_000..10_300).map { it to (1000.0 - (it - 10_000)) * 0.001 })
            val dbColumn = converter.convertToDatabaseColumn(book)
            val loaded = converter.convertToEntityAttribute(dbColumn)
            assertNotNull(loaded)
            assertArrayEquals(book.serialize(), loaded!!.serialize())
        }

        // ---- perf / memory ----
        @Test fun `perf roundtrip throughput and allocation overhead`() {
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            assumeTrue(bean.isThreadAllocatedMemorySupported)

            val a = arr((0 until 4000).map { it to (it * 1.5 - 7.0) })
            val bytes = a.serialize()

            val convOp: (Int) -> Unit = {
                val b = converter.convertToDatabaseColumn(a)!!
                converter.convertToEntityAttribute(b)
            }
            val rawOp: (Int) -> Unit = {
                val b = a.serialize()
                IntDoubleEntangledArray.deserialize(b)
            }

            repeat(2000) { convOp(it); rawOp(it) }
            val iterations = 20_000
            val convBytes = bytesPerOp(iterations, convOp)
            val rawBytes = bytesPerOp(iterations, rawOp)

            val t0 = System.nanoTime()
            repeat(iterations) { convOp(it) }
            val nsPerOp = (System.nanoTime() - t0).toDouble() / iterations

            println("[IntDouble] convert bytes/op=${"%.0f".format(convBytes)} raw bytes/op=${"%.0f".format(rawBytes)} ns/op=${"%.0f".format(nsPerOp)} payload=${bytes.size}B")
            assertTrue(convBytes <= rawBytes * 1.25 + 256,
                "converter adds gross allocation overhead: conv=$convBytes raw=$rawBytes")
        }
    }

    // =============================================================================================
    // DoubleDoubleEntangledArrayConverter
    // =============================================================================================
    @Nested
    inner class DoubleDoubleConverterTests {
        private val converter = DoubleDoubleEntangledArrayConverter()
        private val EMPTY = DoubleDoubleEntangledArray.EMPTY

        private fun arr(vararg pairs: Pair<Double, Double>) = DoubleDoubleEntangledArray(pairs.toList())
        private fun arr(pairs: List<Pair<Double, Double>>) = DoubleDoubleEntangledArray(pairs)

        private fun roundTrip(a: DoubleDoubleEntangledArray) {
            val bytes = converter.convertToDatabaseColumn(a)
            assertNotNull(bytes)
            val back = converter.convertToEntityAttribute(bytes)
            assertNotNull(back)
            assertArrayEquals(a.serialize(), back!!.serialize(),
                "round-trip changed the canonical byte form")
        }

        // ---- null contract ----
        @Test fun `toDatabaseColumn null is null`() {
            assertNull(converter.convertToDatabaseColumn(null))
        }

        @Test fun `toEntityAttribute null is null`() {
            assertNull(converter.convertToEntityAttribute(null))
        }

        // ---- delegation / consistency ----
        @Test fun `toDatabaseColumn equals serialize bytes`() {
            val a = arr(1.0 to 1.5, 2.0 to 2.5, 3.0 to 3.5)
            assertArrayEquals(a.serialize(), converter.convertToDatabaseColumn(a))
        }

        @Test fun `toEntityAttribute equals deserialize content`() {
            val a = arr(5.0 to 0.1, 6.0 to 0.2, 7.0 to 0.3)
            val bytes = a.serialize()
            val viaConverter = converter.convertToEntityAttribute(bytes)!!
            val viaStatic = DoubleDoubleEntangledArray.deserialize(bytes)
            assertArrayEquals(viaStatic.serialize(), viaConverter.serialize())
        }

        @Test fun `delegation is faithful for many shapes`() {
            val shapes = listOf(
                arr(),
                arr(7.5 to 3.14),
                arr(1.0 to 1.0, 2.0 to 2.0, 3.0 to 3.0),
                arr(-5.5 to -1.5, 5.5 to 1.5),
            )
            for (s in shapes) assertArrayEquals(s.serialize(), converter.convertToDatabaseColumn(s))
        }

        @Test fun `serialized size is 4 plus 16 per entry`() {
            val a = arr((1..7).map { it.toDouble() to it.toDouble() })
            assertEquals(4 + 7 * 16, converter.convertToDatabaseColumn(a)!!.size)
        }

        // ---- empty ----
        @Test fun `empty toDatabaseColumn is non-null and round-trips to EMPTY singleton`() {
            val bytes = converter.convertToDatabaseColumn(EMPTY)
            assertNotNull(bytes)
            assertEquals(4, bytes!!.size)
            assertSame(EMPTY, converter.convertToEntityAttribute(bytes))
        }

        @Test fun `empty round-trip`() { roundTrip(EMPTY) }

        // ---- round-trip shapes ----
        @Test fun `roundtrip single element`() { roundTrip(arr(0.0 to 1.0)) }
        @Test fun `roundtrip single negative level`() { roundTrip(arr(-42.5 to 7.25)) }
        @Test fun `roundtrip single negative quantity`() { roundTrip(arr(3.3 to -99.99)) }
        @Test fun `roundtrip ascending levels`() {
            roundTrip(arr((1..50).map { it.toDouble() to it * 0.5 }))
        }
        @Test fun `roundtrip fractional levels`() {
            roundTrip(arr(0.001 to 1.0, 0.0025 to 2.0, 3.14159 to 3.0, 271.828 to 4.0))
        }
        @Test fun `roundtrip negative levels`() {
            roundTrip(arr((-20..-1).map { it.toDouble() to (it * 0.3) }))
        }
        @Test fun `roundtrip levels spanning zero`() {
            roundTrip(arr((-10..10).map { it.toDouble() + 0.5 to (it + 1.0) }))
        }
        @Test fun `roundtrip duplicate-fused levels`() {
            val a = arr(5.0 to 1.0, 5.0 to 2.0, 5.0 to 3.0, 6.0 to 10.0)
            assertEquals(6.0, a[5.0], 0.0)
            roundTrip(a)
        }
        @Test fun `roundtrip extreme level values`() {
            roundTrip(arr(-Double.MAX_VALUE to 1.0, 0.0 to 5.0, Double.MAX_VALUE to 2.0))
        }
        @Test fun `roundtrip extreme double quantities`() {
            roundTrip(arr(1.0 to Double.MAX_VALUE, 2.0 to Double.MIN_VALUE, 3.0 to -Double.MAX_VALUE))
        }
        @Test fun `roundtrip Infinity level and quantity preserved`() {
            // levels are sorted; +Inf is the largest finite-or-infinite level, stored raw.
            val a = arr(1.0 to 1.0, 2.0 to Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY to 9.0)
            roundTrip(a)
            val back = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(a))!!
            assertEquals(Double.POSITIVE_INFINITY, back[2.0])
            assertEquals(9.0, back[Double.POSITIVE_INFINITY])
        }
        @Test fun `roundtrip NaN quantity preserved by raw double serialization`() {
            val a = arr(1.0 to Double.NaN, 2.0 to 5.0)
            roundTrip(a) // byte equality verifies NaN bit pattern survives
            val back = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(a))!!
            assertTrue(back[1.0].isNaN())
        }
        @Test fun `roundtrip negative zero quantity bit pattern preserved`() {
            val a = DoubleDoubleEntangledArray(listOf(1.0 to -0.0, 2.0 to 5.0), allowZero = true)
            roundTrip(a)
            val back = converter.convertToEntityAttribute(converter.convertToDatabaseColumn(a))!!
            assertEquals(java.lang.Double.doubleToRawLongBits(-0.0),
                java.lang.Double.doubleToRawLongBits(back[1.0]))
        }
        @Test fun `roundtrip allowZero keeps zero entries`() {
            val a = DoubleDoubleEntangledArray(listOf(1.0 to 0.0, 2.0 to 5.0, 3.0 to 0.0), allowZero = true)
            assertEquals(3, a.size)
            roundTrip(a)
        }
        @Test fun `roundtrip large array`() {
            roundTrip(arr((0 until 2000).map { (it * 0.01) to ((it - 1000) * 1.25) }))
        }
        @Test fun `roundtrip large array irregular levels`() {
            val rnd = Random(3)
            val levels = sortedSetOf<Double>()
            while (levels.size < 800) levels.add(rnd.nextDouble() * 1e6)
            roundTrip(arr(levels.map { it to rnd.nextDouble() * 1e6 }))
        }
        @Test fun `roundtrip closely spaced fractional levels`() {
            roundTrip(arr((0 until 500).map { (1.0 + it * 1e-9) to it.toDouble() }))
        }

        // ---- annotation / contract ----
        @Test fun `is annotated Converter autoApply true`() {
            val ann = converter.javaClass.getAnnotation(Converter::class.java)
            assertNotNull(ann)
            assertTrue(ann!!.autoApply)
        }

        @Test fun `implements AttributeConverter`() {
            assertTrue(converter is AttributeConverter<*, *>)
        }

        // ---- integration ----
        @Test fun `integration persist load cycle`() {
            val book = arr((0 until 300).map { (29_000.0 + it * 0.5) to (10.0 - it * 0.01) })
            val dbColumn = converter.convertToDatabaseColumn(book)
            val loaded = converter.convertToEntityAttribute(dbColumn)
            assertNotNull(loaded)
            assertArrayEquals(book.serialize(), loaded!!.serialize())
            assertEquals(book.toLevelValueList(), loaded.toLevelValueList())
        }

        // ---- perf / memory ----
        @Test fun `perf roundtrip throughput and allocation overhead`() {
            val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
            assumeTrue(bean.isThreadAllocatedMemorySupported)

            val a = arr((0 until 4000).map { (it * 0.5) to (it * 1.5 - 7.0) })
            val bytes = a.serialize()

            val convOp: (Int) -> Unit = {
                val b = converter.convertToDatabaseColumn(a)!!
                converter.convertToEntityAttribute(b)
            }
            val rawOp: (Int) -> Unit = {
                val b = a.serialize()
                DoubleDoubleEntangledArray.deserialize(b)
            }

            repeat(2000) { convOp(it); rawOp(it) }
            val iterations = 20_000
            val convBytes = bytesPerOp(iterations, convOp)
            val rawBytes = bytesPerOp(iterations, rawOp)

            val t0 = System.nanoTime()
            repeat(iterations) { convOp(it) }
            val nsPerOp = (System.nanoTime() - t0).toDouble() / iterations

            println("[DoubleDouble] convert bytes/op=${"%.0f".format(convBytes)} raw bytes/op=${"%.0f".format(rawBytes)} ns/op=${"%.0f".format(nsPerOp)} payload=${bytes.size}B")
            assertTrue(convBytes <= rawBytes * 1.25 + 256,
                "converter adds gross allocation overhead: conv=$convBytes raw=$rawBytes")
        }
    }
}
