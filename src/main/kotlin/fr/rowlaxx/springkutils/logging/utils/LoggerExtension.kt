package fr.rowlaxx.springkutils.logging.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Provides an easy way to access a logger for any class.
 */
object LoggerExtension {
    private val loggers = ConcurrentHashMap<Class<*>, Logger>()

    /**
     * Lazily gets or creates a [Logger] instance for the receiver's class.
     * Loggers are cached in a [ConcurrentHashMap] for efficiency.
     */
    val <T : Any> T.log: Logger
        get() = loggers.computeIfAbsent(javaClass, LoggerFactory::getLogger)

}