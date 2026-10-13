package com.github.oinsio.gnomish.app

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.git.ContainerHarvestFetch
import com.github.oinsio.gnomish.adapter.git.LocalBoxEnvironment
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist
import com.github.oinsio.gnomish.sandbox.ResourceLimits
import com.github.oinsio.gnomish.sandbox.environment.BoxEquipmentFixture
import com.github.oinsio.gnomish.sandbox.environment.BoxGitLink
import com.github.oinsio.gnomish.sandbox.environment.ContainerTaskExecutionEnvironment
import com.github.oinsio.gnomish.sandbox.environment.DockerCli
import com.github.oinsio.gnomish.sandbox.environment.HostExecHandle
import com.github.oinsio.gnomish.testfixtures.TestChildEnvironment
import com.github.oinsio.gnomish.testfixtures.standin.StandIn
import java.time.Duration

/**
 * NFR-R1 of add-sandbox-core, FR10 of bound-subprocess-commands, NFR-R1 of
 * fix-denial-report-attachment, over a real sandboxed round (the engine, the round adapters, a box
 * over a real clone): a runtime that stops answering, or a denial source that cannot be read, is
 * an infrastructure matter — it never burns an attempt and never fails a record or a report.
 */
class SandboxRoundOutageSpec extends RoundFlowSpecBase {

    /** What the container adapter's exec starts once the daemon is gone: the docker client, crying unreachable. */
    private static HostExecHandle dockerClientWithoutDaemon() {
        def builder = new ProcessBuilder(StandIn.docker('docker-daemon-down').toString(), 'exec', 'gnomish-box-task-1')
        TestChildEnvironment.cleared(builder)
        builder.redirectErrorStream(true)
        new HostExecHandle(builder.start(), LocalBoxEnvironment.EXEC_STAMP)
    }

    // F84: scenario text aligned to what this test asserts — no retry: the second launch is the last one.
    // Scenario: sandbox/execution-environment / Docker daemon dies mid-task
    def "NFR-R1: a daemon gone between rounds makes the next round cannot-execute, with the attempt count unchanged"() {
        given: 'a first round that fails its check, after which the daemon stops answering'
        def box = localBox()
        def launches = 0
        box.agentLaunchAnswer = { command ->
            ++launches> 1 ? dockerClientWithoutDaemon() : box.box.exec(command)
        }

        when:
        def outcome = run(pipeline(3), box, 'plain-round', ScriptedCommandCheckRunner.fixed(new Verdict.Fail([])))

        then: 'the second round could not execute and was not retried: the task escalates at once as cannot-execute'
        launches == 2
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.CannotExecute

        and: 'only the first round, a quality failure, counted against the stage'
        outcome.finalState().attemptsUsed() == 1
    }

    // D14 of tighten-spec-contract (F85): the agent of the second round still reports a result, so
    //     without the close-of-round probe that round would be graded and burn the second attempt
    // Scenario: sandbox/sandbox-egress / Guard crash burns no attempt
    def "NFR-R1: a guard that dies while a round runs makes it cannot-execute, with the attempt count unchanged"() {
        given: 'a first round that fails its check; the guard then dies while the second round runs'
        def box = localBox()
        box.guardDiesAtProbe = 3

        when:
        def outcome = run(pipeline(3), box, 'plain-round', ScriptedCommandCheckRunner.fixed(new Verdict.Fail([])))

        then: 'the second round was probed at its close and not graded: the task escalates as cannot-execute'
        box.guardProbes == 4
        outcome instanceof TaskOutcome.Escalated
        def report = (outcome as TaskOutcome.Escalated).report()
        report instanceof EscalationReport.CannotExecute

        and: 'the operator-visible reason names the guard container'
        (report as EscalationReport.CannotExecute).cause().forConsole().contains('gnomish-guard-task-1')

        and: 'only the first round, a quality failure, counted against the stage'
        outcome.finalState().attemptsUsed() == 1
    }

    // Scenario: sandbox/execution-environment / A wedged registry does not hang the take
    def "FR10: a box whose docker never answers ends the round on the docker deadline, as cannot-execute"() {
        given: 'a container box whose docker accepts every command and then never answers, bounded at one second'
        def stalling = new DockerCli(StandIn.docker('docker-stall').toString(), Duration.ofSeconds(1))
        def box = new ContainerTaskExecutionEnvironment(
                BoxEquipmentFixture.forBox(stalling, 'gnomish/absent-image', new ResourceLimits('1', '64m', 32L, '1g'), new VirtualClock()),
                'wedged-' + System.nanoTime(), new BoxGitLink(clone, new ContainerHarvestFetch(runner, clone)), ChildEnvAllowlist.none(), 'proj-1')

        when:
        def started = System.nanoTime()
        def outcome = run(pipeline(3), box, 'plain-round', new ScriptedCommandCheckRunner())
        def elapsed = Duration.ofNanos(System.nanoTime() - started)

        then: 'the take came back within a few docker deadlines, not the stall'
        elapsed <Duration.ofSeconds(30)

        and: 'as an infrastructure failure: cannot-execute, no attempt burned'
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.CannotExecute
        outcome.finalState().attemptsUsed() == 0
    }

    // Scenario: sandbox/execution-environment / An unreadable denial source fails no attempt and no report
    def "NFR-R1: with the denial source unreadable, a failed attempt is recorded with an empty denial list"() {
        given:
        def box = localBox()
        box.denialSourceUnreadable = true

        when:
        def outcome = run(pipeline(1), box, 'plain-round', ScriptedCommandCheckRunner.fixed(new Verdict.Fail([])))

        then: 'the attempt was recorded and the stage exhausted normally'
        outcome instanceof TaskOutcome.Escalated
        (outcome as TaskOutcome.Escalated).report() instanceof EscalationReport.AttemptsExhausted
        outcome.finalState().attempts()*.denials() == [[]]

        and: 'the durable record on the branch carries the empty list too'
        def state = new ObjectMapper().readTree(gitOutput(clone, 'show', tip() + ':.gnomish-task/state.json'))
        state.get('attempts').collect { it.get('denials').toString() } == ['[]']
    }

    // Scenario: sandbox/execution-environment / An unreadable denial source fails no attempt and no report
    def "NFR-R1: with the denial source unreadable, a cannot-execute report is built with an empty denial list"() {
        given:
        def box = localBox()
        box.denialSourceUnreadable = true

        when: 'the round cannot execute — the agent ends without a result'
        def outcome = run(pipeline(3), box, 'missing-result-event', new ScriptedCommandCheckRunner())

        then:
        outcome instanceof TaskOutcome.Escalated
        def report = (outcome as TaskOutcome.Escalated).report()
        report instanceof EscalationReport.CannotExecute
        (report as EscalationReport.CannotExecute).denials() == []
    }
}
