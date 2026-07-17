package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.adapter.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.port.contract.CheckRunnerContract
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.TempDir

/**
 * The real {@code files_exist} runner (add-manual-run FR6) subclasses the SAME
 * port-contract suite the scripted fake passes: every {@link Verdict} variant
 * is reachable through a real {@link DirectoryWorkspace} and real files, so no
 * row is skipped.
 *
 * <p>Implements FR14, M2 of add-manual-run.
 */
class FilesExistCheckRunnerContractSpec extends CheckRunnerContract {

    @TempDir
    Path tempDir

    def runner = new FilesExistCheckRunner()

    private DirectoryWorkspace workspace() {
        new DirectoryWorkspace(tempDir)
    }

    @Override
    protected Optional<Verdict> arrange(CheckRunnerContract.VerdictVariant variant) {
        VerifyCheck.Builtin check = switch (variant) {
                    case CheckRunnerContract.VerdictVariant.PASS -> {
                        Files.writeString(tempDir.resolve('present.txt'), 'ok')
                        yield new VerifyCheck.Builtin('files_exist', [files: ['present.txt']])
                    }
                    case CheckRunnerContract.VerdictVariant.FAIL_WITH_FINDINGS ->
                    new VerifyCheck.Builtin('files_exist', [files: ['missing.txt']])
                    case CheckRunnerContract.VerdictVariant.CANNOT_VERIFY ->
                    new VerifyCheck.Builtin('files_exist', [:])
                }
        Optional.of(runner.run(check, workspace()))
    }

    @Override
    protected String portName() {
        'BuiltinCheckRunner (FilesExistCheckRunner)'
    }
}
