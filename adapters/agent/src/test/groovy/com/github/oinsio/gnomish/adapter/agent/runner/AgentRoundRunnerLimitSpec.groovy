package com.github.oinsio.gnomish.adapter.agent.runner

import com.github.oinsio.gnomish.adapter.agent.program.ProgramResultMapping
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.executorcontract.FailureClass
import com.github.oinsio.gnomish.executorcontract.Role
import com.github.oinsio.gnomish.executorcontract.Status
import com.github.oinsio.gnomish.executorcontract.UsageProvenance
import com.github.oinsio.gnomish.status.StatusReport
import com.github.oinsio.gnomish.status.StatusTextRenderer
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Timeout

/**
 * A turn or budget stop of an agent round (FR2, FR8, UX2 of extract-agent-round-runner): the dialect
 * reports the bound it stopped on, the runner reports {@code failed/limit} whose reason is exactly that
 * bound, and the engine's shape of it — a {@code LIMIT} failure, which the engine's classifier ends the
 * stage on without burning an attempt and records as a budget stop — renders in the escalation report
 * as {@code budget exhausted: <bound>}.
 */
@Timeout(60)
class AgentRoundRunnerLimitSpec extends Specification {

    @TempDir
    Path workspace

    @TempDir
    Path stderrParent

    // Scenario: executor/agent-round-runner / Turn limit is a limit stop
    def "FR8, UX2: a turn-limit stop is failed/limit naming the bound, and its escalation report reads budget exhausted"() {
        given:
        def fixture = new RunnerFixture(workspace: workspace, stderrParent: stderrParent)

        when:
        def result = fixture.start(RunnerFixture.request(Role.TRANSFORM),
                RunnerFixture.exited(1, 'hello m s\nuse Edit\nstop turn bound of 20 reached\n'))

        then: 'the contract result names the bound, keeps the usage, and carries no answer'
        result.status() == Status.FAILED
        result.failureClass() == FailureClass.LIMIT
        result.reason().raw() == 'turn bound of 20 reached'
        result.usage().provenance() == UsageProvenance.REPORTED
        result.answer() == null

        when: 'the result takes the engine shape the round wrapper maps it to'
        def engine = ProgramResultMapping.toEngine(RunnerFixture.NAME, Role.TRANSFORM, new AttemptKey('TASK-7', 'build', 0),
                result, [])
        def failed = engine.status() as ExecutionResult.Failed

        then: 'a limit stop, whose report reads budget exhausted: <bound>'
        failed.failureClass() == ExecutionResult.FailureClass.LIMIT
        failed.findings().isEmpty()
        report(new EscalationReport.BudgetExhausted(failed.reason())).contains('budget exhausted: turn bound of 20 reached')
    }

    private static String report(EscalationReport escalation) {
        def context = new TaskContext('TASK-7', UntrustedText.tracker('title'), UntrustedText.tracker('body'), [])
        new StatusTextRenderer().renderFull(StatusReport.build(context, TaskState.atStageStart('build'), escalation, null))
    }
}
