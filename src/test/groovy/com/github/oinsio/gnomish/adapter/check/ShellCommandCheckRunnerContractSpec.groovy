package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.contract.CheckRunnerContract
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Path
import spock.lang.TempDir

/**
 * The real command runner (add-manual-run FR7/FR8/D6) subclasses the SAME
 * port-contract suite the scripted fake passes: every {@link Verdict} variant
 * is reachable through real {@code sh -c} exit codes against a real {@link
 * DirectoryWorkspace}, so no row is skipped.
 *
 * <p>Implements FR14, M2 of add-manual-run.
 */
class ShellCommandCheckRunnerContractSpec extends CheckRunnerContract {

    @TempDir
    Path tempDir

    def runner = new ShellCommandCheckRunner()

    private DirectoryWorkspace workspace() {
        new DirectoryWorkspace(tempDir)
    }

    @Override
    protected Optional<Verdict> arrange(CheckRunnerContract.VerdictVariant variant) {
        String commandLine = switch (variant) {
                    case CheckRunnerContract.VerdictVariant.PASS -> 'exit 0'
                    case CheckRunnerContract.VerdictVariant.FAIL_WITH_FINDINGS -> 'echo boom-output; exit 1'
                    case CheckRunnerContract.VerdictVariant.CANNOT_VERIFY -> 'exit 127'
                }
        Optional.of(runner.run(new VerifyCheck.Command(commandLine), workspace()))
    }

    @Override
    protected String portName() {
        'CommandCheckRunner (ShellCommandCheckRunner)'
    }
}
