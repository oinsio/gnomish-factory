package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner
import com.github.oinsio.gnomish.adapter.check.ShellExecutor
import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.fixture.VerifyChecks
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.sandbox.environment.HostTaskExecutionEnvironment
import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.StdinSource
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * FR12, UX4 of the command-check bound, on the contract path (task 4.2f of
 * migrate-verify-to-executor-contract): a {@code shell} check whose command never exits is a check
 * round of the {@code shell} built-in, killed on the check timeout, answering {@code failed/quality}
 * with the output tail captured so far; the one contract → domain mapping makes it the round's one
 * finding, the tail in its details — a quality failure, not a hang and not cannot-verify.
 */
class ShellCheckRoundTimeoutSpec extends Specification {

    @TempDir
    Path tempDir

    // Scenario: verification/check-provider-model / A hung check fails instead of hanging the run
    @Timeout(30)
    def "FR12, UX4: a check round whose command never exits fails as quality, its tail in the finding's details"() {
        given: 'a command that printed a line and never exits; the drain reads the line before the wait ends'
        def process = FakeProcess.running()
        boolean drainReadTheLine = false
        process.onWaitReturn {
            drainReadTheLine = process.awaitStdoutRead(Duration.ofSeconds(10))
        }
        def launch = { ProcessBuilder builder, StdinSource stdin ->
            process.writeStdout('hung-output\n')
            process
        } as ProcessLaunch
        def runner = new ShellCommandCheckRunner(new VirtualClock()).withCheckTimeout(Duration.ofMillis(300))
                .withEnvironments(hostEnvironments(launch))
        def check = VerifyChecks.command('the-hung-check')
        def round = new CheckRound(new ShellExecutor(runner, check, new DirectoryWorkspace(tempDir)), null, check)

        when:
        def result = round.execute(new StageExecutor.Request(new TaskContext('TASK-1', UntrustedText.tracker('t'),
                UntrustedText.tracker('b'), []), new StageDefinition('build', 'p', [], [],
                new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'm', [:]), 'i.md', [check], new AutonomyLimits(1),
                AdvancementMode.AUTO), new DirectoryWorkspace(tempDir), 0, [], check, CheckRef.of(0, check), null))

        then: 'a quality failure whose one finding names the timeout and carries the tail'
        def failed = result.status() as ExecutionResult.Failed
        failed.failureClass() == ExecutionResult.FailureClass.QUALITY
        failed.findings().size() == 1
        failed.findings()[0].message().toLowerCase().contains('timed out')
        failed.findings()[0].details().contains('hung-output')

        and: 'the drain read the line through the pipe'
        process.awaitStdoutRead(Duration.ZERO)
    }

    /** The host environment of a check, its process started by {@code launch} instead of the OS. */
    private CheckEnvironmentSource hostEnvironments(ProcessLaunch launch) {
        def host = new HostTaskExecutionEnvironment(tempDir, new VirtualClock(), ChildEnvAllowlist.none(), launch)
        host.materialize('workspace:fake', null)
        Stub(CheckEnvironmentSource) {
            acquire(_, _) >> Stub(CheckEnvironmentSource.Acquired) {
                environment() >> host
                close() >> { host.dispose() }
            }
        }
    }
}
