# Spring K Utils

A collection of Kotlin utilities for Spring Boot applications, focusing on concurrency, scheduling, and logging.

## Features

### 🚀 CompletableFuture Extensions
Provides a more idiomatic Kotlin API for `CompletableFuture`.

```kotlin
val future = CompletableFuture.supplyAsync { "Hello" }
    .onCompleted { "$it World" }
    .onError { "Error: ${it.message}" }
    .onCancelled { "Cancelled" }
```

Available extensions:
- `onCompleted`, `composeOnCompleted`
- `onError`, `composeOnError`
- `onCancelled`, `composeOnCancelled`
- `onDone`, `composeOnDone`

### 🧵 Sequential Workers
Ensures that tasks are executed one after another in a given `ExecutorService`, even when submitted from multiple threads. Supports both synchronous and asynchronous tasks.

```kotlin
val worker = SequentialWorker(executorService)

// Sync task
worker.submitTask {
    println("Task 1 running")
}

// Async task - worker waits for the future to complete before starting next task
worker.submitAsyncTask {
    CompletableFuture.supplyAsync {
        println("Task 2 running")
    }
}
```

Use `SequentialWorkerPool` to manage multiple workers indexed by keys (e.g., user IDs, resource IDs). Idle workers are automatically retired and removed from the pool.

```kotlin
val pool = SequentialWorkerPool(executorService)
val userWorker = pool["user-123"]
userWorker.submitTask { /* ... */ }
```

### 📝 Logger Extension
Quick access to an SLF4J logger for any class.

```kotlin
class MyService {
    fun doSomething() {
        log.info("Doing something...")
    }
}
```

### 🗓️ Scheduling Configuration
Automatically configures a `ThreadPoolTaskScheduler` with a pool size of 4 and a thread name prefix "Scheduler " when Spring's `@EnableScheduling` is used.

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("fr.rowlaxx:spring-k-utils:1.0.0")
}
```

## Requirements
- Java 23+
- Kotlin 2.0+
- Spring Boot 3.4+

## License
MIT
