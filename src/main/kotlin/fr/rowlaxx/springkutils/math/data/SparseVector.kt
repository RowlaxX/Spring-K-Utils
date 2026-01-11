package fr.rowlaxx.springkutils.math.data

import java.util.TreeMap
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Represents a math vector with infinite dimension.
 * Only non-zero values are stored.
 */
open class SparseVector(
    internal val content: TreeMap<Int, Double> = TreeMap()
) {
    /**
     * Gets the value at the specified [index].
     * Returns 0.0 if the value is not present.
     */
    operator fun get(index: Int): Double = content.getOrDefault(index, 0.0)

    /**
     * Returns a list of values for the specified [range].
     */
    fun getAll(range: IntRange): List<Double> = range.map { get(it) }

    /**
     * Returns the number of non-zero elements in the vector.
     */
    fun nonZeroCount(): Int = content.size

    /**
     * Returns the first index where the value is 0.0.
     * Since the vector has infinite dimension, it returns the first non-stored index.
     */
    fun firstZeroIndex(): Int {
        var expected = 0
        for (index in content.keys) {
            if (index > expected) return expected
            if (index == expected) expected++
        }
        return expected
    }

    /**
     * Returns the last index where the value is 0.0.
     * Since the vector has infinite dimension, this is theoretically infinite.
     * Returning [Int.MAX_VALUE] if it's not stored, otherwise looking for the last one.
     */
    fun lastZeroIndex(): Int {
        if (content.isEmpty()) return Int.MAX_VALUE
        val last = content.lastKey()
        if (last < Int.MAX_VALUE) return Int.MAX_VALUE
        
        // If Int.MAX_VALUE is taken, we look for the first gap from the end.
        var expected = Int.MAX_VALUE
        val descendingKeys = content.descendingKeySet()
        for (index in descendingKeys) {
            if (index < expected) return expected
            if (expected == Int.MIN_VALUE) return -1
            expected--
        }
        return expected 
    }

    /**
     * Returns the first non-zero index, or -1 if the vector is empty.
     */
    fun firstNonZeroIndex(): Int = if (content.isEmpty()) -1 else content.firstKey()

    /**
     * Returns the last non-zero index, or -1 if the vector is empty.
     */
    fun lastNonZeroIndex(): Int = if (content.isEmpty()) -1 else content.lastKey()

    /**
     * Returns the next non-zero index starting from [from] (inclusive).
     */
    fun nextNonZeroIndex(from: Int): Int = content.ceilingKey(from) ?: -1

    /**
     * Returns the previous non-zero index starting from [from] (inclusive).
     */
    fun previousNonZeroIndex(from: Int): Int = content.floorKey(from) ?: -1

    /**
     * Returns the next zero index starting from [from] (inclusive).
     */
    fun nextZeroIndex(from: Int): Int {
        var current = from
        while (content.containsKey(current)) {
            if (current == Int.MAX_VALUE) return -1
            current++
        }
        return current
    }

    /**
     * Returns the previous zero index starting from [from] (inclusive).
     */
    fun previousZeroIndex(from: Int): Int {
        var current = from
        while (content.containsKey(current)) {
            if (current == Int.MIN_VALUE) return -1
            current--
        }
        return current
    }

    /**
     * Element-wise multiplication of this vector and [other].
     */
    infix fun dot(other: SparseVector): SparseVector {
        val result = TreeMap<Int, Double>()
        val smaller = if (this.content.size < other.content.size) this else other
        val larger = if (this.content.size < other.content.size) other else this
        
        for ((index, value) in smaller.content) {
            val otherValue = larger.content[index]
            if (otherValue != null) {
                val prod = value * otherValue
                if (prod != 0.0) result[index] = prod
            }
        }
        return SparseVector(result)
    }

    /**
     * Cross product of this vector and [other].
     * Only works for 3D vectors (indices 0, 1, 2).
     */
    infix fun cross(other: SparseVector): SparseVector {
        val result = TreeMap<Int, Double>()
        
        val a0 = this[0]
        val a1 = this[1]
        val a2 = this[2]
        
        val b0 = other[0]
        val b1 = other[1]
        val b2 = other[2]
        
        val r0 = a1 * b2 - a2 * b1
        val r1 = a2 * b0 - a0 * b2
        val r2 = a0 * b1 - a1 * b0
        
        if (r0 != 0.0) result[0] = r0
        if (r1 != 0.0) result[1] = r1
        if (r2 != 0.0) result[2] = r2
        
        return SparseVector(result)
    }

    /**
     * Returns the Euclidean norm (length) of the vector.
     */
    fun norm(): Double {
        var sum = 0.0
        for (value in content.values) {
            sum += value * value
        }
        return sqrt(sum)
    }

    /**
     * Returns a normalized version of this vector (norm = 1.0).
     */
    fun normalized(): SparseVector {
        val n = norm()
        check(n != 0.0) { "Cannot normalize a zero vector" }
        return multiplied(1.0 / n)
    }

    /**
     * Returns the sum of this vector and [other].
     */
    infix operator fun plus(other: SparseVector): SparseVector {
        val result = TreeMap(content)
        for ((index, value) in other.content) {
            val newValue = result.getOrDefault(index, 0.0) + value
            if (newValue == 0.0) result.remove(index) else result[index] = newValue
        }
        return SparseVector(result)
    }

    /**
     * Returns the difference of this vector and [other].
     */
    infix operator fun minus(other: SparseVector): SparseVector {
        val result = TreeMap(content)
        for ((index, value) in other.content) {
            val newValue = result.getOrDefault(index, 0.0) - value
            if (newValue == 0.0) result.remove(index) else result[index] = newValue
        }
        return SparseVector(result)
    }

    /**
     * Returns a vector containing the absolute values of this vector.
     */
    fun abs(): SparseVector {
        val result = TreeMap<Int, Double>()
        for ((index, value) in content) {
            result[index] = abs(value)
        }
        return SparseVector(result)
    }

    /**
     * Returns the Euclidean distance between this vector and [other].
     */
    fun distance(other: SparseVector): Double {
        return (this - other).norm()
    }

    /**
     * Returns this vector multiplied by a scalar [value].
     */
    infix fun multiplied(value: Double): SparseVector {
        if (value == 0.0) return SparseVector()
        val result = TreeMap<Int, Double>()
        for ((index, v) in content) {
            val newValue = v * value
            if (newValue != 0.0) result[index] = newValue
        }
        return SparseVector(result)
    }

    /**
     * Returns this vector divided by a scalar [value].
     */
    infix fun divided(value: Double): SparseVector {
        require(value != 0.0) { "Division by zero" }
        return multiplied(1.0 / value)
    }

    /**
     * Performs the given [action] on each non-zero element.
     */
    fun forEachNonZero(action: (Int, Double) -> Unit) {
        content.forEach(action)
    }

    /**
     * Performs the given [action] on each element in the specified [range].
     */
    fun forEach(range: IntRange, action: (Int, Double) -> Unit) {
        for (i in range) {
            action(i, get(i))
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SparseVector) return false
        return content == other.content
    }

    override fun hashCode(): Int {
        return content.hashCode()
    }

    override fun toString(): String {
        return "SparseVector($content)"
    }

    /**
     * Returns an immutable view of this vector.
     */
    fun immutableView(): SparseVector {
        if (this !is MutableSparseVector) return this
        return SparseVector(content)
    }

    /**
     * Returns an immutable copy of this vector.
     */
    fun immutableCopy(): SparseVector {
        return SparseVector(TreeMap(content))
    }

    /**
     * Returns a mutable copy of this vector.
     */
    fun copy(): MutableSparseVector {
        val newContent = TreeMap(content)
        return MutableSparseVector(newContent)
    }
}