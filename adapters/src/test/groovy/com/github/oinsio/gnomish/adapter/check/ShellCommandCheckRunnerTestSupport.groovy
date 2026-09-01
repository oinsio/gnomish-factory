package com.github.oinsio.gnomish.adapter.check

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path

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

    /**
     * Migrated to the shared helper (`.claude/rules/logging.md`) when task 5.4 touched these specs.
     * Pinned at DEBUG: several of the lines under assertion here are per-item detail that the
     * level policy puts below the console.
     */
    static List<ILoggingEvent> capture(Class<?> loggerOwner, Closure<?> emit) {
        def logs = LogCaptureSupport.attach(loggerOwner, Level.DEBUG)
        try {
            emit()
            return List.copyOf(logs.list)
        } finally {
            logs.detach()
        }
    }
}
