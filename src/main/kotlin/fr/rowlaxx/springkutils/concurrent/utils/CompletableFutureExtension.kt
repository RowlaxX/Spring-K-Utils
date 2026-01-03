package fr.rowlaxx.springkutils.concurrent.utils

import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Extension functions for [CompletableFuture] to provide a more idiomatic Kotlin API.
 */
object CompletableFutureExtension {

    /**
     * Executes the [action] if the future is cancelled.
     *
     * @param action The action to execute.
     * @return A new [CompletableFuture] that completes with the result of the [action] if the original future is cancelled,
     * or completes with the same result/exception as the original future otherwise.
     */
    fun <T, U> CompletableFuture<T>.onCancelled(action: () -> U): CompletableFuture<U> = handle { res, ex ->
        if (isCancelled) {
            action()
        } else if (ex != null) {
            throw ex
        } else {
            @Suppress("UNCHECKED_CAST")
            res as U
        }
    }

    /**
     * Executes the [action] when the future completes successfully.
     *
     * @param action The action to execute with the result of the future.
     * @return A new [CompletableFuture] that completes with the result of the [action].
     */
    fun <T, U> CompletableFuture<T>.onCompleted(action: (T) -> U): CompletableFuture<U> = thenApply(action)

    /**
     * Executes the [action] if the future completes with an error (and is not cancelled).
     *
     * @param action The action to execute with the cause of the error.
     * @return A new [CompletableFuture] that completes with the result of the [action] if the original future fails,
     * or completes with the same result as the original future if it succeeds.
     */
    fun <T, U> CompletableFuture<T>.onError(action: (Throwable) -> U): CompletableFuture<U> = handle { res, ex ->
        if (ex != null && !isCancelled) {
            action(if (ex is CompletionException) ex.cause ?: ex else ex)
        } else if (isCancelled) {
            throw CancellationException()
        } else {
            @Suppress("UNCHECKED_CAST")
            res as U
        }
    }

    /**
     * Asynchronously executes the [action] if the future is cancelled.
     *
     * @param action The action returning a [CompletableFuture].
     * @return A new [CompletableFuture] that completes with the result of the future returned by [action] if the original future is cancelled.
     */
    fun <T, U> CompletableFuture<T>.composeOnCancelled(action: () -> CompletableFuture<U>): CompletableFuture<U> {
        val result = CompletableFuture<U>()
        this.whenComplete { res, ex ->
            if (this.isCancelled) {
                try {
                    action().whenComplete { res2, ex2 ->
                        if (ex2 != null) result.completeExceptionally(ex2)
                        else result.complete(res2)
                    }
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                }
            } else if (ex != null) {
                result.completeExceptionally(ex)
            } else {
                @Suppress("UNCHECKED_CAST")
                result.complete(res as U)
            }
        }
        return result
    }

    /**
     * Asynchronously executes the [action] when the future completes successfully.
     *
     * @param action The action returning a [CompletableFuture].
     * @return A new [CompletableFuture] that completes with the result of the future returned by [action].
     */
    fun <T, U> CompletableFuture<T>.composeOnCompleted(action: (T) -> CompletableFuture<U>): CompletableFuture<U> = thenCompose(action)

    /**
     * Asynchronously executes the [action] if the future completes with an error (and is not cancelled).
     *
     * @param action The action returning a [CompletableFuture].
     * @return A new [CompletableFuture] that completes with the result of the future returned by [action] if the original future fails.
     */
    fun <T, U> CompletableFuture<T>.composeOnError(action: (Throwable) -> CompletableFuture<U>): CompletableFuture<U> {
        val result = CompletableFuture<U>()
        this.whenComplete { res, ex ->
            if (ex != null && !this.isCancelled) {
                val cause = if (ex is CompletionException) ex.cause ?: ex else ex
                try {
                    action(cause).whenComplete { res2, ex2 ->
                        if (ex2 != null) result.completeExceptionally(ex2)
                        else result.complete(res2)
                    }
                } catch (e: Throwable) {
                    result.completeExceptionally(e)
                }
            } else if (this.isCancelled) {
                result.cancel(true)
            } else {
                @Suppress("UNCHECKED_CAST")
                result.complete(res as U)
            }
        }
        return result
    }

    /**
     * Executes the [action] when the future is done (successfully, with error, or cancelled).
     *
     * @param action The action to execute.
     * @return A new [CompletableFuture] that completes with [Unit] when the [action] is finished.
     */
    fun <T> CompletableFuture<T>.onDone(action: () -> Unit): CompletableFuture<Unit> = handle { _, _ -> action() }

    /**
     * Asynchronously executes the [action] when the future is done (successfully, with error, or cancelled).
     *
     * @param action The action returning a [CompletableFuture].
     * @return A new [CompletableFuture] that completes when the future returned by [action] is finished.
     */
    fun <T> CompletableFuture<T>.composeOnDone(action: () -> CompletableFuture<Unit>): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        this.whenComplete { _, _ ->
            try {
                action().whenComplete { res2, ex2 ->
                    if (ex2 != null) result.completeExceptionally(ex2)
                    else result.complete(res2)
                }
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

}