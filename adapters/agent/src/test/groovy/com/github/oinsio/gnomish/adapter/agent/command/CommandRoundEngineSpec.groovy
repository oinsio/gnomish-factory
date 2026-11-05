package com.github.oinsio.gnomish.adapter.agent.command

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.operatorevent.OperatorEvent
import com.github.oinsio.gnomish.sandbox.environment.FakeProcess
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * The {@code command} built-in's statuses as the engine receives them (FR6, FR7, NFR-R2, UX1 of
 * add-command-executor), driven by the real engine over the shared round wrapper in a real host
 * environment ({@link CommandEngineHarness}), so no feature rests on a thread order. The
 * contract's one mapping turns the built-in's reason into the engine's finding or escalation cause.
 */
@Timeout(60)
class CommandRoundEngineSpec extends Specification {

    @TempDir
    Path workspace

    /** The engine's one operator line for a round that cannot run past its failure (NFR-O1: unchanged). */
    def warnings = LogCaptureSupport.attach('com.github.oinsio.gnomish.domain.engine.RoundFailures', Level.WARN)
    CommandEngineHarness harness

    def setup() {
        harness = new CommandEngineHarness(workspace)
    }

    def cleanup() {
        warnings.detach()
    }

    /** The one WARN the engine wrote for the escalated round: its existing event, naming the command. */
    void escalatedOnce() {
        assert warnings.list.size() == 1
        assert warnings.list[0].level == Level.WARN
        assert warnings.list[0].formattedMessage.startsWith(OperatorEvent.EXECUTOR_REPORTED_FAILURE.head())
        assert warnings.list[0].formattedMessage.contains("command './gradlew build'")
    }

    TaskOutcome run(Map<String, Object> settings = [:]) {
        harness.run(settings)
    }

    // Scenario: executor/command-executor / Non-zero exit is a quality failure with the output tail
    def "FR6, UX1: an exit 1 after build errors burns the attempt with one finding naming exit code 1 and carrying the tail"() {
        given: 'the first round fails the build; the second passes, and so does its check'
        harness.processes << CannedCommandHandle.exited(1, 'Foo.java:3: error: cannot find symbol\nBUILD FAILED\n') << CannedCommandHandle.exited(0)
        harness.builtinRunner.scripted << new Verdict.Pass()

        when:
        def outcome = run()

        then: 'the first round is a quality failure whose single finding is the built-in\'s reason'
        def first = harness.persisted[0]
        first.attemptsUsed() == 1
        first.attempts()[0].result() == AttemptRecord.Result.QUALITY_FAILURE
        def findings = (first.attempts()[0].checkResults()[0].verdict() as Verdict.Fail).findings()
        findings.size() == 1
        findings[0].message() == "command './gradlew build' failed with exit code 1; its output ended with:\n" +
                'Foo.java:3: error: cannot find symbol\nBUILD FAILED'

        and: 'the stage completed on the second round, and a quality failure is no operator event'
        outcome instanceof TaskOutcome.Completed
        harness.launched.size() == 2
        warnings.list.isEmpty()
    }

    // Scenario: executor/command-executor / Unrunnable command is infrastructure
    // Scenario: executor/command-executor / Non-executable command is infrastructure
    def "FR7: an exit #code escalates as cannot execute naming the command, with no attempt burned"() {
        given:
        harness.processes << CannedCommandHandle.exited(code, "sh: ./gradlew: ${meaning}\n")

        when:
        def outcome = run()

        then:
        outcome instanceof TaskOutcome.Escalated
        def report = outcome.report() as EscalationReport.CannotExecute
        report.cause().raw().startsWith("command './gradlew build' cannot execute: exit code ${code} (${meaning})".toString())
        outcome.finalState().attemptsUsed() == 0
        harness.persisted.isEmpty()
        harness.builtinRunner.calls.isEmpty()
        escalatedOnce()

        where:
        code | meaning
        126 | 'not executable'
        127 | 'not found'
    }

    // Scenario: executor/command-executor / Unrunnable command is infrastructure
    def "FR7: a command that cannot be spawned at all escalates as cannot execute naming the command, with no attempt burned"() {
        given:
        harness.processes << new IOException('error=2, No such file or directory')

        when:
        def outcome = run()

        then:
        outcome instanceof TaskOutcome.Escalated
        (outcome.report() as EscalationReport.CannotExecute).cause().raw() ==
                "command './gradlew build' could not be started: could not start process: sh"
        outcome.finalState().attemptsUsed() == 0
        harness.persisted.isEmpty()
        escalatedOnce()
    }

    // Scenario: executor/command-executor / Hung command cannot hang the engine
    def "FR7: a command that outlives its roundTimeout has its process tree killed and escalates as infrastructure, attemptsUsed unchanged"() {
        given: 'a command that never exits on its own, under a bound of a tenth of a second'
        harness.processes << FakeProcess.running()

        when:
        def outcome = run([roundTimeout: 'PT0.1S'])

        then: 'the supervisor ended the process: the polite signal\'s exit, so nothing is left running'
        !harness.launched[0].isAlive()
        harness.launched[0].exitValue() == 143

        and:
        outcome instanceof TaskOutcome.Escalated
        (outcome.report() as EscalationReport.CannotExecute).cause().raw() ==
                "command './gradlew build' outlived its roundTimeout of PT0.1S and its process tree was killed; it printed nothing"
        outcome.finalState().attemptsUsed() == 0
        harness.persisted.isEmpty()
        harness.builtinRunner.calls.isEmpty()
        escalatedOnce()
    }
}
