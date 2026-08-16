package com.github.oinsio.gnomish

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import spock.lang.Specification

/**
 * Log level is configurable without recompilation (FR4 scenario "Log level
 * from configuration"). Each spec boots the real context with a
 * {@code logging.level.com.github.oinsio.gnomish} property, then emits one
 * DEBUG event strictly through the SLF4J API and captures what Logback lets
 * through with a ListAppender: the same bytecode produces a DEBUG record only
 * when configuration says so — proving SLF4J API, Logback backend, and
 * level-from-configuration in one loop. The "or environment" leg is the same
 * relaxed-binding mechanism already proven in FactoryEnvironmentOverrideSpec:
 * LOGGING_LEVEL_COM_GITHUB_OINSIO_GNOMISH maps to this exact property.
 *
 * <p>Both specs pin their level explicitly (DEBUG here, INFO in the
 * companion below) because the Logback level registry is JVM-global and
 * Spring Boot applies {@code logging.level.*} at context startup: relying on
 * "no property set" would leak whichever level a previously started test
 * context configured, making the suite order-dependent.
 * Implements FR4 of add-project-skeleton.
 */
@SpringBootTest(classes = FactoryApplication, properties = 'logging.level.com.github.oinsio.gnomish=DEBUG')
class LoggingLevelSpec extends Specification {

    // FR4: DEBUG messages appear when the configured level is DEBUG
    def "a DEBUG event emitted through the SLF4J API reaches the Logback output"() {
        when: 'a DEBUG message is emitted via the SLF4J API'
        List<ILoggingEvent> events = Slf4jDebugProbe.emitDebugAndCapture('debug-enabled-probe')

        then: 'Logback delivered it, carrying the original message'
        events.size() == 1
        events[0].formattedMessage == 'debug-enabled-probe'
        events[0].level == Level.DEBUG
    }

    // FR4: the SLF4J API is backed by Logback (stack contract, not just levels)
    def "the SLF4J logger is backed by a Logback logger"() {
        expect: 'the logger obtained via the SLF4J API is a Logback implementation'
        LoggerFactory.getLogger(Slf4jDebugProbe.LOGGER_NAME) instanceof Logger
    }
}

/**
 * Companion context at INFO: the identical emission is suppressed, proving
 * the DEBUG visibility above is driven by configuration alone — same
 * bytecode, no recompilation. Implements FR4 of add-project-skeleton.
 */
@SpringBootTest(classes = FactoryApplication, properties = 'logging.level.com.github.oinsio.gnomish=INFO')
class LoggingLevelSuppressedSpec extends Specification {

    // FR4: the same DEBUG emission is filtered out when the level is INFO
    def "a DEBUG event emitted through the SLF4J API is suppressed at INFO"() {
        when: 'the same DEBUG emission is performed under an INFO-configured context'
        List<ILoggingEvent> events = Slf4jDebugProbe.emitDebugAndCapture('debug-suppressed-probe')

        then: 'Logback filtered it out'
        events.isEmpty()
    }
}

/**
 * Emits one DEBUG event through the SLF4J API on a logger inside the
 * configured package tree and returns the events Logback actually delivered.
 * The appender is attached to the underlying Logback logger only for the
 * duration of the single emission.
 */
class Slf4jDebugProbe {

    static final String LOGGER_NAME = 'com.github.oinsio.gnomish.LoggingLevelSpec'

    static List<ILoggingEvent> emitDebugAndCapture(String message) {
        org.slf4j.Logger slf4jLogger = LoggerFactory.getLogger(LOGGER_NAME)
        Logger logbackLogger = (Logger) slf4jLogger
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            slf4jLogger.debug(message) // strictly through the SLF4J API
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }
}
