package com.github.oinsio.gnomish.adapter.check

import com.github.oinsio.gnomish.app.port.check.CheckEnvironmentSource
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR7, D6 of add-manual-run; FR5 of migrate-verify-to-executor-contract: the real command runner
 * judges a completed run's exit code as status, never as a verdict ({@link ShellOutcome}, which
 * {@link ShellExecutor} renders as the executor contract's result). A green command with no
 * findings is an empty SARIF answer; a red one that wrote no valid result answers one synthetic
 * {@code error} result carrying its output tail; exit 126/127 (shell convention for not-executable
 * / not-found) is no answer at all. What the findings path carries is pinned by {@code
 * ShellCommandCheckRunnerFindingsSpec}, the child environment by {@code
 * ShellCommandCheckRunnerEnvironmentSpec}; process-spawning mechanics by {@code CommandRunSpec}.
 */
class ShellCommandCheckRunnerSpec extends Specification implements ShellCommandCheckRunnerTestSupport {

    @TempDir
    Path tempDir

    def runner = new ShellCommandCheckRunner(new VirtualClock())

    def "probe(...) answers a green command with no findings as an empty answer"() {
        given:
        def check = command('exit 0')

        when:
        def outcome = runner.probe(check, workspace())

        then:
        outcome == new ShellOutcome.Answered([])
        derived(outcome) == new Verdict.Pass(null, [])
    }

    // Scenario: verification/verdict-executors / A red command without a findings file carries its output tail
    def "probe(...) answers a red command without a findings file with one error result carrying the output tail"() {
        given: 'a command that exits 1 and writes a distinctive tail without a findings file'
        def check = command('echo boom-output; exit 1')

        when:
        def outcome = runner.probe(check, workspace())

        then: 'one error result whose details hold the tail'
        def results = answerOf(outcome).results()
        results.size() == 1
        results[0].level().name() == 'ERROR'
        results[0].finding().message() == 'command exited with status 1'
        results[0].finding().details().contains('boom-output')

        and: 'the check fails'
        derived(outcome) == new Verdict.Fail([results[0].finding()])
    }

    def "probe(...) judges exit 126 unanswerable"() {
        given: 'a command that prints a distinctive tail before exiting 126'
        def check = command('echo tail-126-output; exit 126')

        when:
        def outcome = runner.probe(check, workspace())

        then:
        outcome instanceof ShellOutcome.Unanswerable
        def cannotVerify = outcome as ShellOutcome.Unanswerable
        cannotVerify.reason().forLog() == 'command not executable (exit 126)'
        cannotVerify.details().forLog().contains('tail-126-output')

        and: 'the sentence is the factory\'s own — what the command printed is nowhere in it (design D3)'
        cannotVerify.reason().provenance() == Provenance.FACTORY
    }

    // Scenario: verification/verdict-executors / A missing binary is an infrastructure failure
    def "probe(...) judges exit 127 unanswerable, which the shell executor answers failed/infrastructure"() {
        given: 'a command that prints a distinctive tail before exiting 127'
        def check = command('echo tail-127-output; exit 127')

        when:
        def outcome = runner.probe(check, workspace())

        then:
        outcome instanceof ShellOutcome.Unanswerable
        def cannotVerify = outcome as ShellOutcome.Unanswerable
        cannotVerify.reason().forLog() == 'command not found (exit 127)'

        and: 'the executor contract result the mapping turns into CannotVerify (ShellExecutorVerdictContractSpec)'
        def result = new ShellExecutor(runner, check, workspace()).start(ShellExecutorDescribeSpec.verdict())
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.INFRASTRUCTURE

        and: 'the sentence is the factory\'s own — what the command printed is nowhere in it (design D3)'
        !cannotVerify.reason().forLog().contains('tail-127-output')
        cannotVerify.reason().provenance() == Provenance.FACTORY
    }

    def "a workspace that is not a DirectoryWorkspace is unanswerable"() {
        given:
        def check = command('pwd')
        def opaqueWorkspace = new Workspace() {}

        when:
        def outcome = runner.probe(check, opaqueWorkspace)

        then:
        outcome instanceof ShellOutcome.Unanswerable
    }

    def "a shell start failure is caught and is unanswerable"() {
        given: 'a runner configured with a nonexistent shell executable'
        def failingRunner = new ShellCommandCheckRunner('/no/such/shell-binary', new VirtualClock())
        def check = command('pwd')

        when:
        ShellOutcome outcome = null
        def events = capture(ShellCommandCheckRunner) {
            outcome = failingRunner.probe(check, workspace())
        }

        then:
        outcome == new ShellOutcome.Unanswerable(
                UntrustedText.manifest('failed to start command: pwd'),
                NoCheckDetails.NO_DETAILS)

        and: 'FR5 of harden-logging-observability: the degradation names the check it happened to'
        def warning = events.find { it.level.levelStr == 'WARN' }
        warning != null
        warning.formattedMessage.startsWith(OperatorEvent.COMMAND_CHECK_PROCESS_START_FAILED.head())
        warning.formattedMessage.contains('failed to start')
        warning.formattedMessage.contains("'pwd'")
    }

    // FR5 of harden-logging-observability: no environment to run a check in is an infrastructure
    // failure the engine only ever sees as a CannotVerify — the operator needs the line and the
    // check that hit it.
    def "an unavailable check environment is warned about, naming the check"() {
        given:
        def unavailable = { c, w ->
            throw new CheckEnvironmentUnavailableException('fresh box could not be materialized')
        } as CheckEnvironmentSource
        def check = command('pwd')

        when:
        ShellOutcome outcome = null
        def events = capture(ShellCommandCheckRunner) {
            outcome = runner.withEnvironments(unavailable).probe(check, workspace())
        }

        then:
        outcome instanceof ShellOutcome.Unanswerable
        (outcome as ShellOutcome.Unanswerable).reason().forLog() == 'fresh box could not be materialized'

        and:
        def warning = events.find { it.level.levelStr == 'WARN' }
        warning != null
        warning.formattedMessage.startsWith(OperatorEvent.COMMAND_CHECK_NO_ENVIRONMENT.head())
        warning.formattedMessage.contains('no environment to run it in')
        warning.formattedMessage.contains("'pwd'")

        and: 'the cause keeps its stack rather than being interpolated'
        warning.throwableProxy.className == CheckEnvironmentUnavailableException.name
    }

    // Scenario: cli/manual-run / Host findings files stay outside the worktree
    def "GNOMISH_FINDINGS_FILE is passed as a path outside the workspace root"() {
        given: 'a command that echoes the env var so the test can inspect its value, run through probe() so the runner creates the temp path itself'
        def check = command('echo $GNOMISH_FINDINGS_FILE; exit 1')

        when:
        def outcome = runner.probe(check, workspace())

        then: 'the synthetic result carries the output tail, which contains the echoed path'
        def path = findingsOf(outcome)[0].details().trim()
        !path.isEmpty()
        !path.startsWith(tempDir.toRealPath().toString())
    }

    // FR1, NFR-S3 of add-sandbox-core (task 7.3): the findings path lives in the environment's
    // scratch area, and dispose removes the whole scratch with it after the verdict is classified.
    def "probe(...) allocates the findings path in scratch and disposes it after classifying the verdict"() {
        given: 'a command that echoes the findings-file path so the test can inspect it afterward'
        def check = command('echo $GNOMISH_FINDINGS_FILE > "$PWD/.path-marker"; exit 0')

        when:
        runner.probe(check, workspace())
        def markerPath = tempDir.resolve('.path-marker')
        def findingsPath = Path.of(Files.readString(markerPath).trim())

        then:
        !Files.exists(findingsPath)
        !Files.exists(findingsPath.parent)
    }
}
