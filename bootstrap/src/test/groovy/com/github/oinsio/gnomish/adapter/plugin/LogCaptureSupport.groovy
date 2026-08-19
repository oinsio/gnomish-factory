package com.github.oinsio.gnomish.adapter.plugin

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Test-only helper: attaches/detaches a Logback {@link ListAppender} to a class's own logger, for
 * specs that assert on the log lines a component emits (e.g. {@link ProviderDiscoveryReport} and its
 * callers). Shared between the report specs that both capture the same logger rather than each
 * repeating the attach/detach boilerplate.
 */
final class LogCaptureSupport {

    private LogCaptureSupport() {}

    static ListAppender<ILoggingEvent> attach(Class<?> loggerClass) {
        def appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger(loggerClass).level = Level.INFO
        logger(loggerClass).addAppender(appender)
        appender
    }

    static void detach(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
        logger(loggerClass).detachAppender(appender)
        appender.stop()
    }

    private static Logger logger(Class<?> loggerClass) {
        LoggerFactory.getLogger(loggerClass) as Logger
    }
}
