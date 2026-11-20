package com.github.oinsio.gnomish.adapter.check

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import com.github.oinsio.gnomish.app.port.check.SarifResultReading
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.VerdictDerivation
import com.github.oinsio.gnomish.domain.pipeline.Threshold
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path

/**
 * Shared Spock helpers for {@link ShellCommandCheckRunner} specs: a workspace rooted at the
 * spec's own temp dir, a shell line wrapped as a {@link VerifyCheck.Executor}, the answer of a run
 * the runner judged {@link ShellOutcome.Answered} as the engine reads it — each result as the
 * executor contract carries it ({@code ShellResult.asSarif}, the rendering {@link ShellExecutor}
 * answers with) read through the one reading of a SARIF result, {@link SarifResultReading} — the
 * verdict derived from it, and capture of a logger's events emitted while a closure runs.
 */
trait ShellCommandCheckRunnerTestSupport {

    abstract Path getTempDir()

    DirectoryWorkspace workspace() {
        new DirectoryWorkspace(tempDir)
    }

    static VerifyCheck.Executor command(String line) {
        VerifyChecks.command(line)
    }

    /**
     * The answer of an answered run as the engine reads it: each result as the executor contract
     * carries it, through the one reading of a SARIF result; the run must have answered.
     */
    static VerdictAnswer.Sarif answerOf(ShellOutcome outcome) {
        assert outcome instanceof ShellOutcome.Answered
        new VerdictAnswer.Sarif((outcome as ShellOutcome.Answered).results().collect {
            SarifResultReading.toDomain(it.asSarif())
        }, null)
    }

    /** The findings of an answered run's results, in the order written. */
    static List<Finding> findingsOf(ShellOutcome outcome) {
        answerOf(outcome).results()*.finding()
    }

    /** The verdict the engine derives from an answered run under the default threshold. */
    static Verdict derived(ShellOutcome outcome) {
        VerdictDerivation.derive(answerOf(outcome), Threshold.ERROR)
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
