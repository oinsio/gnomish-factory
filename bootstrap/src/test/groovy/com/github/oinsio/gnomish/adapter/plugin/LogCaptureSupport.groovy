package com.github.oinsio.gnomish.adapter.plugin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Test-only helper: attaches a Logback {@link ListAppender} to a class's own logger, for specs that
 * assert on the log lines a component emits (e.g. {@link ProviderDiscoveryReport} and its callers).
 * Shared between the report specs that both capture the same logger rather than each repeating the
 * attach/detach boilerplate.
 *
 * Capturing pins the logger at INFO, since that is the level the reports are written at. The
 * Logback level registry is JVM-global, so {@link #detach} restores whatever level the logger
 * carried before — leaving the pin behind would make any later spec asserting on this logger's
 * configured level order-dependent.
 */
final class LogCaptureSupport {

    private final Logger logger
    private final ListAppender<ILoggingEvent> appender
    private final Level previousLevel

    /** Starts capturing the given class's logger at INFO. */
    static LogCaptureSupport attach(Class<?> loggerClass) {
        new LogCaptureSupport(loggerClass)
    }

    private LogCaptureSupport(Class<?> loggerClass) {
        logger = LoggerFactory.getLogger(loggerClass) as Logger
        previousLevel = logger.level
        appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.level = Level.INFO
        logger.addAppender(appender)
    }

    /** The events captured so far. */
    List<ILoggingEvent> getList() {
        appender.list
    }

    /** Stops capturing and restores the logger's previous level. */
    void detach() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }
}
