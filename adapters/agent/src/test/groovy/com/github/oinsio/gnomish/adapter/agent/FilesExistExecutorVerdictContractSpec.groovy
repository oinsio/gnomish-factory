package com.github.oinsio.gnomish.adapter.agent

import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner
import com.github.oinsio.gnomish.adapter.check.FilesExistExecutor
import com.github.oinsio.gnomish.adapter.check.NoCheckDetails
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.BuiltInCheckRounds
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.ResultLevel
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.VerdictAnswer
import com.github.oinsio.gnomish.domain.engine.port.contract.CheckRunnerContract
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.TempDir

/**
 * The {@code files-exist} built-in passes the same check-runner contract suite the {@code shell}
 * built-in passes, on the path production runs: a {@code verdict} start of {@link FilesExistExecutor}
 * over the real {@link FilesExistCheckRunner}, its contract result mapped onto the engine's shape by
 * the one contract → domain mapping ({@link ProgramResultMapping}), the round's answer read as the
 * engine's check round reads it ({@link BuiltInCheckRounds}). Every {@link Verdict} variant is
 * reachable through a real {@link DirectoryWorkspace} and real files, so no row is skipped.
 *
 * <p>Implements FR14, M2 of add-manual-run; FR1, FR7 of migrate-verify-to-executor-contract.
 */
class FilesExistExecutorVerdictContractSpec extends CheckRunnerContract {

    @TempDir
    Path tempDir

    /** The engine's answer of one check round of {@code check} in the spec's working copy. */
    private VerdictAnswer answer(VerifyCheck.Executor check) {
        BuiltInCheckRounds.answerOf(round(check))
    }

    private ExecutionResult round(VerifyCheck.Executor check) {
        BuiltInCheckRounds.run(new FilesExistExecutor(new FilesExistCheckRunner(), new DirectoryWorkspace(tempDir)), check)
    }

    @Override
    protected Optional<VerdictAnswer> arrange(CheckRunnerContract.VerdictVariant variant) {
        VerifyCheck.Executor check = switch (variant) {
                    case CheckRunnerContract.VerdictVariant.PASS -> {
                        Files.writeString(tempDir.resolve('present.txt'), 'ok')
                        yield VerifyChecks.builtin('files_exist', [files: ['present.txt']])
                    }
                    case CheckRunnerContract.VerdictVariant.FAIL_WITH_FINDINGS ->
                    VerifyChecks.builtin('files_exist', [files: ['missing.txt']])
                    case CheckRunnerContract.VerdictVariant.CANNOT_VERIFY ->
                    VerifyChecks.builtin('files_exist', [:])
                }
        Optional.of(answer(check))
    }

    @Override
    protected String portName() {
        'files-exist built-in (FilesExistExecutor through ProgramResultMapping)'
    }

    // FR7 of migrate-verify-to-executor-contract: the engine receives one error result per missing path
    def "the engine receives one error result per missing path"() {
        given:
        Files.writeString(tempDir.resolve('present.txt'), 'ok')
        def check = VerifyChecks.builtin('files_exist', [files: [
                'missing.txt',
                'present.txt',
                'gone.txt'
            ]])

        expect:
        answer(check) == new VerdictAnswer.Sarif([
            new VerdictAnswer.Result(ResultLevel.ERROR, new Finding('missing file: missing.txt', 'missing.txt', null)),
            new VerdictAnswer.Result(ResultLevel.ERROR, new Finding('missing file: gone.txt', 'gone.txt', null))
        ])
    }

    // FR1, D5 (human decision 2026-11-10): the engine receives the answer within the result cap
    def "the engine receives more missing paths than the result cap within the cap"() {
        given:
        def paths = (1..1001).collect {
            "m${it}.txt".toString()
        }

        when:
        def answer = answer(VerifyChecks.builtin('files_exist', [files: paths])) as VerdictAnswer.Sarif

        then:
        answer.results().size() == 1000
        answer.results().take(999)*.finding() == paths.take(999).collect {
            new Finding("missing file: ${it}".toString(), it, null)
        }
        answer.results().last() == new VerdictAnswer.Result(
                ResultLevel.ERROR, new Finding('and 2 more missing files not listed', null, null))
    }

    // FR1: an unanswerable probe reaches the engine as failed/infrastructure with no details
    def "the engine receives an unanswerable probe as an infrastructure failure with no details"() {
        given:
        def check = VerifyChecks.builtin('files_exist', [files: ['../secret']])

        when:
        def failed = round(check).status() as ExecutionResult.Failed

        then:
        failed.failureClass() == ExecutionResult.FailureClass.INFRASTRUCTURE
        failed.findings().isEmpty()
        answer(check) == new VerdictAnswer.InfrastructureFailure(
                UntrustedText.manifest('files_exist path escapes the workspace: ../secret'), NoCheckDetails.NO_DETAILS)
    }
}
