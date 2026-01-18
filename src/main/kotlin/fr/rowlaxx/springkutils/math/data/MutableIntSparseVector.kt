package fr.rowlaxx.springkutils.math.data

import java.util.TreeMap

/**
 * Represents a mutable math vector with infinite dimension using Int values.
 * Only non-zero values are stored.
 */
class MutableIntSparseVector(
    content: TreeMap<Int, Int> = TreeMap()
) : IntSparseVector(content) {

    /**
     * Sets the [value] at the specified [index].
     * If the value is 0, it is removed from the storage.
     */
    operator fun set(index: Int, value: Int) {
        if (value == 0) {
            content.remove(index)
        } else {
            content[index] = value
        }
    }

    /**
     * Sets the [value] for all indices in the specified [range].
     */
    fun setAll(range: IntRange, value: Int) {
        for (index in range) {
            set(index, value)
        }
    }

    /**
     * Adds [value] to the element at the specified [index].
     */
    fun add(index: Int, value: Int) {
        set(index, get(index) + value)
    }

    /**
     * Adds [value] to all elements in the specified [indexRange].
     */
    fun addAll(indexRange: IntRange, value: Int) {
        for (index in indexRange) {
            add(index, value)
        }
    }

    /**
     * Subtracts [value] from the element at the specified [index].
     */
    fun sub(index: Int, value: Int) {
        set(index, get(index) - value)
    }

    /**
     * Subtracts [value] from all elements in the specified [indexRange].
     */
    fun subAll(indexRange: IntRange, value: Int) {
        for (index in indexRange) {
            sub(index, value)
        }
    }

    /**
     * Multiplies all non-zero elements by [value].
     */
    fun multiply(value: Int) {
        if (value == 0) {
            content.clear()
            return
        }
        val it = content.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val newValue = entry.value * value
            if (newValue == 0) {
                it.remove()
            } else {
                entry.setValue(newValue)
            }
        }
    }

    /**
     * Divides all non-zero elements by [value].
     */
    fun divide(value: Int) {
        require(value != 0) { "Division by zero" }
        val it = content.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val newValue = entry.value / value
            if (newValue == 0) {
                it.remove()
            } else {
                entry.setValue(newValue)
            }
        }
    }

    /**
     * Adds [other] vector to this one.
     */
    fun add(other: IntSparseVector) {
        other.forEachNonZero { index, value ->
            add(index, value)
        }
    }

    /**
     * Subtracts [other] vector from this one.
     */
    fun sub(other: IntSparseVector) {
        other.forEachNonZero { index, value ->
            sub(index, value)
        }
    }

    /**
     * Transforms all non-zero elements using the given [action].
     */
    fun transformNonZero(action: (Int, Int) -> Int) {
        val it = content.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val newValue = action(entry.key, entry.value)
            if (newValue == 0) {
                it.remove()
            } else {
                entry.setValue(newValue)
            }
        }
    }

    /**
     * Transforms all elements in the specified [range] using the given [action].
     */
    fun transform(range: IntRange, action: (Int, Int) -> Int) {
        for (index in range) {
            set(index, action(index, get(index)))
        }
    }

    fun toIntSparseVector(): IntSparseVector {
        return IntSparseVector(TreeMap(content))
    }
}
