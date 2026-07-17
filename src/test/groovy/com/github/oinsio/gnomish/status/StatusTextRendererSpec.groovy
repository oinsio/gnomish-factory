package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * StatusTextRenderer: renders a StatusReport as human-readable English text
 * (task 6.4 of add-manual-run) — a full multi-line report and a one-line
 * per-attempt summary. Implements FR10, UX2, D7 of add-manual-run.
 */
class StatusTextRendererSpec extends Specification {

    private static final Instant STARTED = Instant.parse('2026-07-16T14:35:10Z')

    private static TaskContext context(List<Decision> decisions = []) {
        new TaskContext('manual-20260716-143502-x7', 'Fix flaky spec', 'body text', decisions)
    }

    private static AttemptRecord passedRound(int round = 0) {
        def check = new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))
        new AttemptRecord(round, AttemptRecord.Result.PASSED, STARTED, [check], ExecutorUsage.none(), JudgeUsage.none())
    }

    private static AttemptRecord failedRound(int round = 0) {
        def check = new CheckResult(new CheckRef(0, 'command:./gradlew test'),
                new Verdict.Fail([]), Duration.ofSeconds(5))
        new AttemptRecord(round, AttemptRecord.Result.QUALITY_FAILURE, STARTED, [check],
        new ExecutorUsage(Duration.ofSeconds(5), [], [:]), JudgeUsage.none())
    }

    // FR10, UX2: renderAttemptSummary is genuinely one line and mentions the round and result
    def "renderAttemptSummary is one line mentioning the round and result"() {
        given:
        def renderer = new StatusTextRenderer()

        when:
        def line = renderer.renderAttemptSummary(failedRound(2))

        then:
        !line.contains('\n')
        line.contains('Round 2')
        line.contains('quality failure')
    }

    // FR10, UX2: renderAttemptSummary highlights check pass/fail counts and duration
    def "renderAttemptSummary highlights a passing round"() {
        given:
        def renderer = new StatusTextRenderer()

        when:
        def line = renderer.renderAttemptSummary(passedRound(0))

        then:
        line.contains('Round 0')
        line.contains('passed')
        line.contains('1 checks passed')
    }

    // FR10, UX2, D7: renderFull includes recognizable content for a fully populated report
    def "renderFull includes stage, attempts, decisions, totals, activity, escalation and last decision"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement').recordQualityFailure(failedRound(0))
        def decision = new Decision('patch in place', 'plan', 'operator', STARTED)
        def ctx = context([decision])
        def escalation = new EscalationReport.DecisionNeeded('Refactor or patch?', ['refactor', 'patch'])
        def activity = new LiveActivity(new Activity.Verifying(new CheckRef(0, 'command:./gradlew test'), STARTED),
                escalation, null)
        def report = StatusReport.build(ctx, state, 3, activity)

        when:
        def text = renderer.renderFull(report)

        then:
        text.contains(ctx.taskId())
        text.contains(ctx.title())
        text.contains('implement')
        text.contains('1/3')
        text.contains('Round 0')
        text.contains('Decisions:')
        text.contains('patch in place')
        text.contains('wallMillis=5000')
        text.contains('verifying command:./gradlew test')
        text.contains('decision needed: Refactor or patch?')
        text.contains('Last decision:')
        text.contains('patch in place')
    }

    // FR11: renderFull renders "pipeline complete" and no attempt-limit fraction at pipelineEnd
    def "renderFull renders pipeline complete with no attempt limit at pipelineEnd"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement').advanceTo(new Position.PipelineEnd())
        def report = StatusReport.build(context(), state, null, LiveActivity.idle())

        when:
        def text = renderer.renderFull(report)

        then:
        text.contains('pipeline complete')
    }

    // FR10, UX2: renderFull omits optional sections that are absent
    def "renderFull omits attempts, decisions, activity, escalation and last-decision sections when absent"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement')
        def report = StatusReport.build(context(), state, 3, LiveActivity.idle())

        when:
        def text = renderer.renderFull(report)

        then:
        !text.contains('Attempts:')
        !text.contains('Decisions:')
        !text.contains('Activity:')
        !text.contains('Last escalation:')
        !text.contains('Last decision:')
    }

    // NFR-C1: renderFull renders "unknown" for null totals rather than fabricating zeros
    def "renderFull renders unknown totals when usage is unreported"() {
        given:
        def renderer = new StatusTextRenderer()
        def report = StatusReport.build(context(), TaskState.atStageStart('implement'), 3, LiveActivity.idle())

        when:
        def text = renderer.renderFull(report)

        then:
        text.contains('wallMillis=unknown')
        text.contains('tokensByModel=unknown')
    }

    // FR11, D7: renderFull renders every EscalationReport kind without throwing
    def "renderFull renders every escalation report kind"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement')

        expect:
        renderer.renderFull(StatusReport.build(context(), state, 3,
                new LiveActivity(null, escalation, null))).contains(expectedFragment)

        where:
        escalation                                                                                | expectedFragment
        new EscalationReport.AttemptsExhausted(3)                                                  | 'attempts exhausted'
        new EscalationReport.DecisionNeeded('Refactor?', ['a', 'b'])                                | 'decision needed'
        new EscalationReport.CannotVerify(new CheckRef(0, 'command:x'), 'network error', '')        | 'cannot verify'
        new EscalationReport.PipelineMismatch('stale-stage')                                        | 'pipeline mismatch'
        new EscalationReport.CannotExecute('agent crashed')                                         | 'cannot execute'
    }

    // FR11, D7: renderFull renders every Activity kind without throwing
    def "renderFull renders every activity kind"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement')

        expect:
        renderer.renderFull(StatusReport.build(context(), state, 3,
                new LiveActivity(activity, null, null))).contains(expectedFragment)

        where:
        activity                                                                  | expectedFragment
        new Activity.Executing(STARTED)                                           | 'executing'
        new Activity.Verifying(new CheckRef(0, 'builtin:files_exist'), STARTED)   | 'verifying builtin:files_exist'
        new Activity.AwaitingInput('pass/fail? ', STARTED)                        | 'awaiting input: "pass/fail? "'
    }

    // FR7, UX1, D10, D12 of add-agent-executor: executing activity renders live tool detail when present
    def "renderFull renders executing activity with currentTool and toolCalls when present"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement')
        def activity = new LiveActivity(new Activity.Executing(STARTED, 'Edit', 3), null, null)
        def report = StatusReport.build(context(), state, 3, activity)

        when:
        def text = renderer.renderFull(report)

        then:
        text.contains('executing')
        text.contains('Edit')
        text.contains('3')
    }

    // FR7, D10, D12 of add-agent-executor: executing activity omits tool detail when absent
    def "renderFull renders plain executing activity when no live tool detail is present"() {
        given:
        def renderer = new StatusTextRenderer()
        def state = TaskState.atStageStart('implement')
        def activity = new LiveActivity(new Activity.Executing(STARTED), null, null)
        def report = StatusReport.build(context(), state, 3, activity)

        when:
        def text = renderer.renderFull(report)

        then:
        text.contains('executing (since')
        !text.contains('tool')
    }
}
