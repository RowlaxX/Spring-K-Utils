package fr.rowlaxx.springkutils.concurrent.utils

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ExecutorsExtensionTest {

    @Test
    fun `should wrap runnable and log exception`() {
        val executor = ExecutorsUtils.newFailsafeScheduledExecutor(1, "test-executor")
        val exceptionThrown = AtomicBoolean(false)
        
        val future = executor.submit {
            throw RuntimeException("Expected exception")
        }

        try {
            future.get(1, TimeUnit.SECONDS)
        } catch (e: Exception) {
            exceptionThrown.set(true)
        }

        assertTrue(exceptionThrown.get())
        executor.shutdown()
    }

    @Test
    fun `should execute task successfully`() {
        val executor = ExecutorsUtils.newFailsafeScheduledExecutor(1, "test-executor")
        val taskExecuted = AtomicBoolean(false)

        val future = executor.submit {
            taskExecuted.set(true)
        }

        future.get(1, TimeUnit.SECONDS)
        assertTrue(taskExecuted.get())
        executor.shutdown()
    }
}
