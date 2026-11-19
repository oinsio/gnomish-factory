package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellExecutor
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.BuiltInCheckRounds
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.contract.CheckRunnerContract
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import java.nio.file.Path
import spock.lang.TempDir

/**
 * The {@code shell} built-in passes the same check-runner contract suite the {@code files-exist}
 * built-in passes, on the path production runs: a {@code verdict} start of {@link ShellExecutor} over
 * the real {@link ShellCommandCheckRunner}, its contract result mapped onto the engine's shape by the
 * one contract → domain mapping ({@link ProgramResultMapping}), the round's answer read as the
 * engine's check round reads it ({@link BuiltInCheckRounds}). Every {@link Verdict} variant is
 * reachable through real {@code sh -c} exit codes, so no row is skipped.
 *
 * <p>Implements FR14, M2 of add-manual-run; FR5, FR7 of migrate-verify-to-executor-contract.
 */
class ShellExecutorVerdictContractSpec extends CheckRunnerContract {

    @TempDir
    Path tempDir

    @Override
    protected Optional<VerdictAnswer> arrange(CheckRunnerContract.VerdictVariant variant) {
        String commandLine = switch (variant) {
                    case CheckRunnerContract.VerdictVariant.PASS -> 'exit 0'
                    case CheckRunnerContract.VerdictVariant.FAIL_WITH_FINDINGS -> 'echo boom-output; exit 1'
                    case CheckRunnerContract.VerdictVariant.CANNOT_VERIFY -> 'exit 127'
                }
        def check = VerifyChecks.command(commandLine)
        def executor = new ShellExecutor(new ShellCommandCheckRunner(new VirtualClock()), check, new DirectoryWorkspace(tempDir))
        Optional.of(BuiltInCheckRounds.answerOf(BuiltInCheckRounds.run(executor, check)))
    }

    @Override
    protected String portName() {
        'shell built-in (ShellExecutor through ProgramResultMapping)'
    }
}
