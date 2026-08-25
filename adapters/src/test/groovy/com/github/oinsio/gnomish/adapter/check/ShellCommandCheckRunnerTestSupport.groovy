package com.github.oinsio.gnomish.adapter.check

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Shared Spock helpers for {@link ShellCommandCheckRunner} specs: a workspace rooted at the
 * spec's own temp dir, a shell line wrapped as a {@link VerifyCheck.Command}, and capture of a
 * logger's events emitted while a closure runs.
 */
trait ShellCommandCheckRunnerTestSupport {

    abstract Path getTempDir()

    DirectoryWorkspace workspace() {
        new DirectoryWorkspace(tempDir)
    }

    static VerifyCheck.Command command(String line) {
        new VerifyCheck.Command(line)
    }

    static List<ILoggingEvent> capture(Class<?> loggerOwner, Closure<?> emit) {
        Logger logbackLogger = (Logger) LoggerFactory.getLogger(loggerOwner)
        ListAppender<ILoggingEvent> appender = new ListAppender<>()
        appender.start()
        logbackLogger.addAppender(appender)
        try {
            emit()
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }
}
