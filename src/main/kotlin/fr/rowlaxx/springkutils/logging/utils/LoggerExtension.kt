package fr.rowlaxx.springkutils.logging.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

object LoggerExtension {
    private val loggers = ConcurrentHashMap<Class<*>, Logger>()

    val <T : Any> T.log: Logger
        get() = loggers.computeIfAbsent(javaClass, LoggerFactory::getLogger)

}