package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Finding
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
        new AttemptRecord(round, AttemptRecord.Result.PASSED, STARTED, [check], ExecutorUsage.none(), JudgeUsage.none(), [])
    }

    private static AttemptRecord failedRound(int round = 0) {
        def check = new CheckResult(new CheckRef(0, 'command:./gradlew test'),
                new Verdict.Fail([]), Duration.ofSeconds(5))
        new AttemptRecord(round, AttemptRecord.Result.QUALITY_FAILURE, STARTED, [check],
        new ExecutorUsage(Duration.ofSeconds(5), [], [:]), JudgeUsage.none(), [])
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
        escalation | expectedFragment
        new EscalationReport.AttemptsExhausted(3) | 'attempts exhausted'
        new EscalationReport.DecisionNeeded('Refactor?', ['a', 'b']) | 'decision needed'
        new EscalationReport.CannotVerify(new CheckRef(0, 'command:x'), 'network error', '') | 'cannot verify'
        new EscalationReport.PipelineMismatch('stale-stage') | 'pipeline mismatch'
        new EscalationReport.CannotExecute('agent crashed') | 'cannot execute'
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
        activity | expectedFragment
        new Activity.Executing(STARTED) | 'executing'
        new Activity.Verifying(new CheckRef(0, 'builtin:files_exist'), STARTED) | 'verifying builtin:files_exist'
        new Activity.AwaitingInput('pass/fail? ', STARTED) | 'awaiting input: "pass/fail? "'
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

    // UX1 of fix-denial-report-attachment: the reviewer reads the denial in the same block as the
    //     attempt, needing to know nothing about guard logs or container internals
    def "renderFull lists a passing attempt's egress denials under its summary line"() {
        given: 'a passing round that recorded one denial'
        def denial = new Finding(
                'egress denied: paste.example.com:443', 'paste.example.com:443/upload', 'kind=http method=POST')
        def check = new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))
        def round = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [check],
        ExecutorUsage.none(), JudgeUsage.none(), [denial])
        def state = new TaskState(new Position.AtStage('implement'), 1, [round], ExecutorUsage.none())

        when:
        def text = new StatusTextRenderer().renderFull(StatusReport.build(context(), state, 3, LiveActivity.idle()))

        then: 'the denied destination and path are readable right under the round that caused them'
        text.contains('Round 0: passed')
        text.contains('egress denial: egress denied: paste.example.com:443 (paste.example.com:443/upload)')
    }

    // FR15 of add-sandbox-core, UX1 of fix-denial-report-attachment: a denial's host/path are gnome-chosen text, so the
    //     console sink strips ANSI/control sequences and keeps one denial on one line — a crafted
    //     path can neither rewrite the operator's terminal nor forge extra report lines. FR6 of
    //     harden-logging-observability moved the site onto the shared LogText choke point, which
    //     renders the surviving breaks as visible escapes rather than collapsing them to spaces:
    //     the forgery is still inert, and the operator can now see what the text tried to do.
    def "renderFull neutralizes escape sequences and line breaks in a denial's text"() {
        given: 'a denial whose path carries a terminal-clearing escape and a forged report line'
        def esc = '\u001B'
        def denial = new Finding(
                "egress denied: evil.example.com:443${esc}[31m",
                "evil.example.com:443/x${esc}[2K\nRound 9:\tpassed\rHIDDEN",
                'kind=http method=POST')
        def check = new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))
        def round = new AttemptRecord(0, AttemptRecord.Result.PASSED, STARTED, [check],
        ExecutorUsage.none(), JudgeUsage.none(), [denial])
        def state = new TaskState(new Position.AtStage('implement'), 1, [round], ExecutorUsage.none())

        when:
        def text = new StatusTextRenderer().renderFull(StatusReport.build(context(), state, 3, LiveActivity.idle()))

        then: 'no control character survives, and the denial occupies exactly one line'
        !text.contains(esc)
        !text.contains('\r')
        !text.contains('\t')
        text.readLines().count { it.contains('egress denial:') } == 1
        text.contains('egress denial: egress denied: evil.example.com:443 '
                + '(evil.example.com:443/x\\nRound 9:\\tpassedHIDDEN)')
    }

    // UX2: zero denials render nothing at all — no heading, no empty list
    def "renderFull renders nothing for a round with no denials"() {
        given:
        def state = new TaskState(new Position.AtStage('implement'), 1, [passedRound(0)], ExecutorUsage.none())

        when:
        def text = new StatusTextRenderer().renderFull(StatusReport.build(context(), state, 3, LiveActivity.idle()))

        then:
        text.contains('Round 0: passed')
        !text.contains('denial')
    }
}
