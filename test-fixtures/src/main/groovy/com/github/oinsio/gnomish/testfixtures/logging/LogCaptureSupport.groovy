package com.github.oinsio.gnomish.testfixtures.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * The project's one way for a spec to assert on the log lines a component emits: attach a Logback
 * {@link ListAppender} to that component's own logger, read the events, detach.
 *
 * <p>It lives in `:test-fixtures` rather than beside any one spec because logging is a
 * cross-cutting contract — every module has emitters whose degrade-path lines are now part of the
 * behavior under test (`.claude/rules/logging.md`), and a helper reachable from only one module
 * leaves the rest hand-rolling the attach/detach block. Moved here by task 2.4 of
 * harden-logging-observability; per NG5 of that change the existing hand-rolled blocks migrate
 * when their spec is next touched, not in a sweep.
 *
 * <p>Capturing pins the logger at INFO by default, since that is the level the anchor and degrade
 * lines are written at; a spec asserting on a DEBUG line pins DEBUG through the two-argument
 * overload of {@link #attach}. The Logback level registry is JVM-global, so {@link #detach}
 * restores whatever level the logger carried before — leaving the pin behind would make any later
 * spec asserting on this logger's configured level order-dependent.
 *
 * <p>Attaching is also how a spec declares the operator lines it expects: the runtime
 * log-expectation gate ({@link LogExpectationGate}, FR17) fails a feature that provokes a
 * WARN/ERROR no capture was watching. There is deliberately no second API for that declaration —
 * a line a spec asserts on and a line a spec expects are the same line, and the gate reads the
 * attachment straight off Logback rather than from a registry this class would have to keep in
 * step.
 *
 * <p>Implements FR11 and NFR-O1 of harden-logging-observability: NFR-O1's "the existing
 * log-capture idiom" is this class, and every observability spec the change added asserts its
 * emitted events through it.
 */
final class LogCaptureSupport {

    private final Logger logger
    private final ListAppender<ILoggingEvent> appender
    private final Level previousLevel

    /** Starts capturing the given class's logger at INFO. */
    static LogCaptureSupport attach(Class<?> loggerClass) {
        new LogCaptureSupport(loggerClass.name, Level.INFO)
    }

    /** Starts capturing the given class's logger at {@code level} — for specs asserting DEBUG detail. */
    static LogCaptureSupport attach(Class<?> loggerClass, Level level) {
        new LogCaptureSupport(loggerClass.name, level)
    }

    /**
     * Starts capturing a logger named directly, for the emitters that log under a category name
     * rather than their own class (the sweep verdict sink's {@code gnomish.sandbox.lifecycle}).
     */
    static LogCaptureSupport attach(String loggerName, Level level = Level.INFO) {
        new LogCaptureSupport(loggerName, level)
    }

    private LogCaptureSupport(String loggerName, Level level) {
        logger = LoggerFactory.getLogger(loggerName) as Logger
        previousLevel = logger.level
        appender = new ListAppender<ILoggingEvent>()
        appender.start()
        logger.level = level
        logger.addAppender(appender)
    }

    /** The events captured so far. */
    List<ILoggingEvent> getList() {
        appender.list
    }

    /**
     * Runs {@code emit} while capturing {@code loggerClass}'s logger at {@code level}, and returns
     * the events it produced. Combines {@link #attach} and {@link #detach} for specs that need only
     * the captured events, not the {@link LogCaptureSupport} instance — the shared shape of the
     * hand-rolled attach/emit/detach block this class exists to replace.
     */
    static List<ILoggingEvent> capture(Class<?> loggerClass, Level level, Closure<?> emit) {
        def logs = attach(loggerClass, level)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }

    /** Stops capturing and restores the logger's previous level. */
    void detach() {
        logger.detachAppender(appender)
        appender.stop()
        logger.level = previousLevel
    }
}
