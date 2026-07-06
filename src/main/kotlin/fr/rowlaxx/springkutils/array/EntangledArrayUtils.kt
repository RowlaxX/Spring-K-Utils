package fr.rowlaxx.springkutils.array

object EntangledArrayUtils {
    private const val INITIAL_SIZE = 1024

    val INT = ArrayUtils.ScratchIntArrayFactory(INITIAL_SIZE)
    val INT_2 = ArrayUtils.ScratchIntArrayFactory(INITIAL_SIZE)
    val INT_3 = ArrayUtils.ScratchIntArrayFactory(INITIAL_SIZE)
    val LONG = ArrayUtils.ScratchLongArrayFactory(INITIAL_SIZE)
    val DOUBLE = ArrayUtils.ScratchDoubleArrayFactory(INITIAL_SIZE)
    val DOUBLE_2 = ArrayUtils.ScratchDoubleArrayFactory(INITIAL_SIZE)
}
