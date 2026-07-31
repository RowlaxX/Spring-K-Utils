package fr.rowlaxx.springkutils.concurrent.aspect

import fr.rowlaxx.springkutils.concurrent.annotation.PreventConcurrentExecution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.stereotype.Component
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@SpringBootTest(classes = [PreventConcurrentExecutionAspectTest.TestConfig::class])
class PreventConcurrentExecutionAspectTest {

    @Autowired
    lateinit var testService: TestService

    @Test
    fun `should prevent multiple execution`() {
        val startLatch = CountDownLatch(1)
        val finishLatch = CountDownLatch(2)
        
        thread {
            testService.longRunningMethod(startLatch)
            finishLatch.countDown()
        }

        // Ensure the first thread has started and is inside the method
        startLatch.await()

        // Call the method again while it's already running
        testService.longRunningMethod(CountDownLatch(0))
        finishLatch.countDown()

        finishLatch.await()

        assertEquals(1, testService.executionCount.get(), "Method should have been executed only once")
    }

    @Configuration
    @EnableAspectJAutoProxy
    class TestConfig {
        @Bean
        fun preventMultipleExecutionAspect() = PreventConcurrentExecutionAspect()

        @Bean
        fun testService() = TestService()
    }

    @Component
    class TestService {
        val executionCount = AtomicInteger(0)

        @PreventConcurrentExecution
        fun longRunningMethod(latch: CountDownLatch) {
            executionCount.incrementAndGet()
            latch.countDown()
            Thread.sleep(100) // Simulate work
        }

        @PreventConcurrentExecution
        fun primitiveReturningMethod(entered: CountDownLatch, release: CountDownLatch): Int {
            entered.countDown()
            release.await()
            return 42
        }
    }

    @Test
    fun `skipped call on primitive-returning method returns zero instead of crashing`() {
        // Regression: the aspect used to return null for a skipped call, which Spring AOP rejects
        // with AopInvocationException when the method's return type is a primitive.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = thread { testService.primitiveReturningMethod(entered, release) }
        entered.await()
        try {
            val skipped = testService.primitiveReturningMethod(CountDownLatch(0), CountDownLatch(0))
            assertEquals(0, skipped, "A skipped concurrent call must return the primitive zero value")
        } finally {
            release.countDown()
            first.join()
        }
    }
}
