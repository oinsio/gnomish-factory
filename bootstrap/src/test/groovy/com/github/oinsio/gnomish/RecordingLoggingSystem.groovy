package com.github.oinsio.gnomish

import org.springframework.boot.logging.LogLevel
import org.springframework.boot.logging.LoggerConfiguration
import org.springframework.boot.logging.LoggingSystem

/**
 * A {@link LoggingSystem} whose shutdown handler only records that it ran — selected for one
 * {@code CommandExitSpec} feature through {@code LoggingSystem.SYSTEM_PROPERTY}, so the assertion
 * that {@code CommandExit} really invokes the active system's teardown can be made without
 * stopping the Logback context the rest of the suite logs through.
 */
class RecordingLoggingSystem extends LoggingSystem {

    static boolean stopped = false

    RecordingLoggingSystem(ClassLoader classLoader) {}

    @Override
    void beforeInitialize() {}

    @Override
    void setLogLevel(String loggerName, LogLevel level) {}

    @Override
    List<LoggerConfiguration> getLoggerConfigurations() {
        []
    }

    @Override
    LoggerConfiguration getLoggerConfiguration(String loggerName) {
        null
    }

    @Override
    Runnable getShutdownHandler() {
        { -> stopped = true }
    }
}
