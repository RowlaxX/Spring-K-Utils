package fr.rowlaxx.springkutils.concurrent.aspect

import fr.rowlaxx.springkutils.concurrent.annotation.PreventMultipleExecution
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

@SpringBootTest(classes = [PreventMultipleExecutionAspectTest.TestConfig::class])
class PreventMultipleExecutionAspectTest {

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
        fun preventMultipleExecutionAspect() = PreventMultipleExecutionAspect()

        @Bean
        fun testService() = TestService()
    }

    @Component
    class TestService {
        val executionCount = AtomicInteger(0)

        @PreventMultipleExecution
        fun longRunningMethod(latch: CountDownLatch) {
            executionCount.incrementAndGet()
            latch.countDown()
            Thread.sleep(100) // Simulate work
        }
    }
}
