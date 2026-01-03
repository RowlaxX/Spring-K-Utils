package fr.rowlaxx.springkutils.concurrent.utils

import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.composeOnCancelled
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.composeOnCompleted
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.composeOnDone
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.composeOnError
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.onCancelled
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.onCompleted
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.onDone
import fr.rowlaxx.springkutils.concurrent.utils.CompletableFutureExtension.onError
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

// Helper for easier testing
private typealias ExecutionException = java.util.concurrent.ExecutionException

class CompletableFutureExtensionTest {

    @Test
    fun `onCompleted should transform value`() {
        val future = CompletableFuture.completedFuture(5)
        val result = future.onCompleted { it * 2 }.get()
        assertEquals(10, result)
    }

    @Test
    fun `onCompleted should propagate error`() {
        val future = CompletableFuture.failedFuture<Int>(RuntimeException("error"))
        val resultFuture = future.onCompleted { it * 2 }
        assertFailsWith<ExecutionException> { resultFuture.get() }
    }

    @Test
    fun `onError should handle error and transform`() {
        val future = CompletableFuture.failedFuture<Int>(RuntimeException("error"))
        // Here T is Int, U is String. Chaining works if we are careful.
        // Actually, if we want to change type on error, the previous result must be compatible with U.
        // Since the future failed, there is no result of type T to be cast to U.
        val result = future.onError { it.message ?: "unknown" }.get()
        assertEquals("error", result)
    }

    @Test
    fun `onCancelled should handle cancellation`() {
        val future = CompletableFuture<Int>()
        future.cancel(true)
        val result = future.onCancelled { 42 }.get()
        assertEquals(42, result)
    }

    @Test
    fun `onDone should run on success`() {
        var called = false
        val future = CompletableFuture.completedFuture(5)
        future.onDone { called = true }.get()
        assertTrue(called)
    }

    @Test
    fun `onDone should run on error`() {
        var called = false
        val future = CompletableFuture.failedFuture<Int>(RuntimeException())
        future.onDone { called = true }.handle { _, _ -> }.get()
        assertTrue(called)
    }

    @Test
    fun `composeOnCompleted should transform value async`() {
        val future = CompletableFuture.completedFuture(5)
        val result = future.composeOnCompleted { CompletableFuture.completedFuture(it * 2) }.get()
        assertEquals(10, result)
    }

    @Test
    fun `composeOnError should handle error async`() {
        val future = CompletableFuture.failedFuture<Int>(RuntimeException("error"))
        val result = future.composeOnError { CompletableFuture.completedFuture(it.message) }.get()
        assertEquals("error", result)
    }

    @Test
    fun `composeOnCancelled should handle cancellation async`() {
        val future = CompletableFuture<Int>()
        future.cancel(true)
        val result = future.composeOnCancelled { CompletableFuture.completedFuture(42) }.get()
        assertEquals(42, result)
    }

    @Test
    fun `composeOnDone should run on success`() {
        var called = false
        val future = CompletableFuture.completedFuture(5)
        future.composeOnDone { 
            called = true
            CompletableFuture.completedFuture(Unit)
        }.get()
        assertTrue(called)
    }

    @Test
    fun `chaining should work`() {
        val future = CompletableFuture.completedFuture(5)
        val result = future
            .onCompleted { it.toString() } // returns CompletableFuture<String>
            .onError { "error" }           // returns CompletableFuture<String>, T=String, U=String
            .onCancelled { "cancelled" }   // returns CompletableFuture<String>, T=String, U=String
            .get()
        assertEquals("5", result)
    }

    @Test
    fun `chaining error should work`() {
        val future = CompletableFuture.failedFuture<Int>(RuntimeException("fail"))
        val result = future
            .onCompleted { it.toString() } // returns CompletableFuture<String>, but this is skipped because of error
            .onError { it.message ?: "error" } // returns CompletableFuture<String>, T=String, U=String
            .get()
        assertEquals("fail", result)
    }

    @Test
    fun `onCancelled should fail if action throws`() {
        val future = CompletableFuture<Int>()
        future.cancel(true)
        val expectedEx = RuntimeException("action failed")
        val resultFuture = future.onCancelled { throw expectedEx }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `onCompleted should fail if action throws`() {
        val future = CompletableFuture.completedFuture(5)
        val expectedEx = RuntimeException("action failed")
        val resultFuture = future.onCompleted { throw expectedEx }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `onError should fail if action throws`() {
        val future = CompletableFuture.failedFuture<Int>(RuntimeException("error"))
        val expectedEx = RuntimeException("action failed")
        val resultFuture = future.onError { throw expectedEx }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `composeOnCancelled should fail if action throws`() {
        val future = CompletableFuture<Int>()
        future.cancel(true)
        val expectedEx = RuntimeException("action failed")
        val resultFuture = future.composeOnCancelled<Int, Int> { throw expectedEx }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `composeOnCompleted should fail if action throws`() {
        val future = CompletableFuture.completedFuture(5)
        val expectedEx = RuntimeException("action failed")
        val resultFuture = future.composeOnCompleted<Int, Int> { throw expectedEx }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `composeOnError should fail if action throws`() {
        val future = CompletableFuture.failedFuture<Int>(RuntimeException("error"))
        val expectedEx = RuntimeException("action failed")
        val resultFuture = future.composeOnError<Int, Int> { throw expectedEx }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `onDone should fail if action throws`() {
        val future = CompletableFuture.completedFuture(5)
        val expectedEx = RuntimeException("action failed")
        val resultFuture = future.onDone { throw expectedEx }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `composeOnDone should fail if action throws`() {
        val future = CompletableFuture.completedFuture(5)
        val expectedEx = RuntimeException("action failed")
        val resultFuture = future.composeOnDone { throw expectedEx }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `composeOnDone should fail if action returns failed future`() {
        val future = CompletableFuture.completedFuture(5)
        val expectedEx = RuntimeException("future failed")
        val resultFuture = future.composeOnDone { CompletableFuture.failedFuture<Unit>(expectedEx) }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `composeOnCompleted should fail if action returns failed future`() {
        val future = CompletableFuture.completedFuture(5)
        val expectedEx = RuntimeException("future failed")
        val resultFuture = future.composeOnCompleted { CompletableFuture.failedFuture<Int>(expectedEx) }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `composeOnError should fail if action returns failed future`() {
        val future = CompletableFuture.failedFuture<Int>(RuntimeException("error"))
        val expectedEx = RuntimeException("future failed")
        val resultFuture = future.composeOnError { CompletableFuture.failedFuture<String>(expectedEx) }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }

    @Test
    fun `composeOnCancelled should fail if action returns failed future`() {
        val future = CompletableFuture<Int>()
        future.cancel(true)
        val expectedEx = RuntimeException("future failed")
        val resultFuture = future.composeOnCancelled { CompletableFuture.failedFuture<Int>(expectedEx) }
        val ex = assertFailsWith<ExecutionException> { resultFuture.get() }
        assertEquals(expectedEx, ex.cause)
    }
}
