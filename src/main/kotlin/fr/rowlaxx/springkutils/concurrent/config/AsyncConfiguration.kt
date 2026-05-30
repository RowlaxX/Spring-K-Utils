package fr.rowlaxx.springkutils.concurrent.config

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import java.util.concurrent.Executor

@Configuration
class AsyncConfiguration(
    private val globalExecutors: GlobalExecutorsConfiguration,
) : AsyncConfigurer {

    override fun getAsyncExecutor(): Executor {
        return globalExecutors.asyncExec
    }

}