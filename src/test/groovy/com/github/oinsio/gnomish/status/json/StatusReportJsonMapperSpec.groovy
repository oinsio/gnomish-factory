package com.github.oinsio.gnomish.status.json

import com.github.oinsio.gnomish.domain.engine.AttemptKey
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
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.status.Activity
import com.github.oinsio.gnomish.status.LiveActivity
import com.github.oinsio.gnomish.status.Outcome
import com.github.oinsio.gnomish.status.StatusReport
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * Verifies {@link StatusReportJsonMapper} against the v1 JSON contract (spec.md):
 * every sealed-type branch, {@code null}-as-null rendering, millisecond durations,
 * ISO-8601 UTC instants, and the {@code status-report-v1.reference.json}
 * byte-identity anchor.
 *
 * FR11, M3: JSON contract v1, reference anchor and versioning policy.
 */
class StatusReportJsonMapperSpec extends Specification {

    def mapper = new StatusReportJsonMapper()

    def "reference anchor: serializing the deterministic sample is byte-identical to status-report-v1.reference.json"() {
        given:
        def referenceText = getClass().getResourceAsStream('/status-report-v1.reference.json').getText('UTF-8')

        expect:
        mapper.serialize(referenceReport()) == referenceText
    }

    def "position renders atStage with the stage name"() {
        given:
        def report = idleReport(new Position.AtStage("implement"))

        expect:
        mapper.toDto(report).position() == new PositionDto.AtStage("atStage", "implement")
    }

    def "position renders pipelineEnd at the end of the pipeline"() {
        given:
        def report = idleReport(new Position.PipelineEnd())

        expect:
        mapper.toDto(report).position() == new PositionDto.PipelineEnd("pipelineEnd")
        mapper.toDto(report).currentStage() == null
    }

    def "activity is null when idle"() {
        given:
        def report = idleReport(new Position.AtStage("implement"))

        expect:
        mapper.toDto(report).activity() == null
    }

    def "activity executing renders with since"() {
        given:
        def since = Instant.parse("2026-07-16T14:41:02Z")
        def report = activityReport(new Activity.Executing(since))

        expect:
        mapper.toDto(report).activity() == new ActivityDto.Executing("executing", "2026-07-16T14:41:02Z")
    }

    def "activity verifying renders checkRef label and since"() {
        given:
        def since = Instant.parse("2026-07-16T14:41:02Z")
        def checkRef = new CheckRef(0, "command:./gradlew test")
        def report = activityReport(new Activity.Verifying(checkRef, since))

        expect:
        mapper.toDto(report).activity() ==
                new ActivityDto.Verifying("verifying", "command:./gradlew test", "2026-07-16T14:41:02Z")
    }

    def "activity awaitingInput renders prompt and since"() {
        given:
        def since = Instant.parse("2026-07-16T14:41:02Z")
        def report = activityReport(new Activity.AwaitingInput("Refactor or patch?", since))

        expect:
        mapper.toDto(report).activity() ==
                new ActivityDto.AwaitingInput("awaitingInput", "Refactor or patch?", "2026-07-16T14:41:02Z")
    }

    def "outcome is null mid-run"() {
        given:
        def report = idleReport(new Position.AtStage("implement"))

        expect:
        mapper.toDto(report).outcome() == null
    }

    def "outcome completed renders bare discriminator"() {
        expect:
        mapper.toDto(outcomeReport(new Outcome.Completed())).outcome() == new OutcomeDto.Completed("completed")
    }

    def "outcome paused renders passedStage"() {
        expect:
        mapper.toDto(outcomeReport(new Outcome.Paused("plan"))).outcome() ==
                new OutcomeDto.Paused("paused", "plan")
    }

    def "outcome escalated renders nested escalation"() {
        given:
        def report = outcomeReport(new Outcome.Escalated(new EscalationReport.AttemptsExhausted(3)))

        expect:
        mapper.toDto(report).outcome() ==
                new OutcomeDto.Escalated("escalated", new EscalationDto.AttemptsExhausted("attemptsExhausted", null, null, 3))
    }

    def "outcome aborted renders failedAt and cause"() {
        given:
        def failedAt = new AttemptKey("task-1", "implement", 2)
        def report = outcomeReport(new Outcome.Aborted(failedAt, "disk full"))

        expect:
        mapper.toDto(report).outcome() == new OutcomeDto.Aborted("aborted", failedAt.toString(), "disk full")
    }

    def "verdict pass renders empty findings and no reason/details"() {
        given:
        def check = new CheckResult(new CheckRef(0, "builtin:files_exist"), new Verdict.Pass(), Duration.ofMillis(3))

        expect:
        AttemptMapper.toCheck(check) ==
                new CheckDto("builtin:files_exist", "pass", [], 3L, null, null)
    }

    def "verdict fail renders findings in full"() {
        given:
        def finding = new Finding("command exited with 1", null, "…output tail…")
        def check = new CheckResult(
                new CheckRef(1, "command:./gradlew test"), new Verdict.Fail([finding]), Duration.ofMillis(41250))

        expect:
        AttemptMapper.toCheck(check) ==
                new CheckDto("command:./gradlew test", "fail",
                [
                    new FindingDto("command exited with 1", null, "…output tail…")
                ], 41250L, null, null)
    }

    def "verdict cannotVerify surfaces reason and details, empty findings"() {
        given:
        def check = new CheckResult(
                new CheckRef(0, "external:ci"), new Verdict.CannotVerify("timeout", "poll exceeded 5m"),
                Duration.ofMillis(300000))

        expect:
        AttemptMapper.toCheck(check) ==
                new CheckDto("external:ci", "cannotVerify", [], 300000L, "timeout", "poll exceeded 5m")
    }

    def "escalation attemptsExhausted renders limit, stage and at are null"() {
        expect:
        EscalationMapper.toDto(new EscalationReport.AttemptsExhausted(3)) ==
                new EscalationDto.AttemptsExhausted("attemptsExhausted", null, null, 3)
    }

    def "escalation decisionNeeded renders question and options"() {
        expect:
        EscalationMapper.toDto(new EscalationReport.DecisionNeeded("Refactor or patch?", ["refactor", "patch"])) ==
        new EscalationDto.DecisionNeeded("decisionNeeded", null, null, "Refactor or patch?", ["refactor", "patch"])
    }

    def "escalation cannotVerify renders check label, reason, details"() {
        given:
        def checkRef = new CheckRef(0, "external:ci")

        expect:
        EscalationMapper.toDto(new EscalationReport.CannotVerify(checkRef, "timeout", "detail")) ==
                new EscalationDto.CannotVerify("cannotVerify", null, null, "external:ci", "timeout", "detail")
    }

    def "escalation pipelineMismatch renders staleStage"() {
        expect:
        EscalationMapper.toDto(new EscalationReport.PipelineMismatch("removed-stage")) ==
                new EscalationDto.PipelineMismatch("pipelineMismatch", null, null, "removed-stage")
    }

    def "escalation cannotExecute renders cause"() {
        expect:
        EscalationMapper.toDto(new EscalationReport.CannotExecute("adapter crashed")) ==
                new EscalationDto.CannotExecute("cannotExecute", null, null, "adapter crashed")
    }

    def "null fields render as JSON null, not omitted"() {
        given:
        def json = mapper.serialize(idleReport(new Position.AtStage("implement")))

        expect:
        json.contains('"outcome" : null')
        json.contains('"lastEscalation" : null')
        json.contains('"lastDecision" : null')
    }

    def "durations render as millisecond longs"() {
        given:
        def usage = new ExecutorUsage(Duration.ofMillis(183000), [], null)

        expect:
        UsageMapper.toUsage(usage).wallMillis() == 183000L
    }

    def "instants render as ISO-8601 UTC strings"() {
        given:
        def since = Instant.parse("2026-07-16T14:41:02Z")
        def report = activityReport(new Activity.Executing(since))

        expect:
        mapper.toDto(report).activity().since() == "2026-07-16T14:41:02Z"
    }

    def "human-only run: null tokens, empty byTool/perVote, wall time present"() {
        given:
        def report = idleReport(new Position.AtStage("implement"))

        when:
        def dto = mapper.toDto(report)

        then:
        dto.totals().tokensIn() == null
        dto.totals().tokensOut() == null
        dto.totals().byTool() == []
    }

    def "byTool maps each ToolUsage's name, calls and duration-in-millis"() {
        given: 'totals reporting a per-tool breakdown with two distinct tools'
        def context = new TaskContext("task-1", "Title", "Body", [])
        def totals = new ExecutorUsage(
                Duration.ofMillis(500),
                [
                    new ToolUsage("shell", 3, Duration.ofMillis(1200)),
                    new ToolUsage("editor", 1, Duration.ofMillis(75))
                ],
                null)
        def state = new TaskState(new Position.AtStage("implement"), 0, [], totals)
        def report = StatusReport.build(context, state, 3, LiveActivity.idle())

        when:
        def byTool = mapper.toDto(report).totals().byTool()

        then:
        byTool.size() == 2
        byTool[0].name() == "shell"
        byTool[0].calls() == 3
        byTool[0].totalMillis() == 1200
        byTool[1].name() == "editor"
        byTool[1].calls() == 1
        byTool[1].totalMillis() == 75
    }

    def "an attempt's judgeUsage.perVote maps each vote's token counts in vote order"() {
        given: 'an attempt whose judge usage carries two cast votes'
        def context = new TaskContext("task-1", "Title", "Body", [])
        def judgeUsage = new JudgeUsage([
            new TokenUsage(100, 20),
            new TokenUsage(150, 30)
        ])
        def attempt = new AttemptRecord(
                0, AttemptRecord.Result.PASSED, Instant.parse("2026-07-17T09:00:00Z"),
                [], ExecutorUsage.none(), judgeUsage)
        def state = new TaskState(new Position.AtStage("implement"), 0, [attempt], ExecutorUsage.none())
        def report = StatusReport.build(context, state, 3, LiveActivity.idle())

        when:
        def perVote = mapper.toDto(report).currentStage().attempts()[0].judgeUsage().perVote()

        then:
        perVote.size() == 2
        perVote[0].tokensIn() == 100
        perVote[0].tokensOut() == 20
        perVote[1].tokensIn() == 150
        perVote[1].tokensOut() == 30
    }

    private static StatusReport idleReport(Position position) {
        def context = new TaskContext("task-1", "Title", "Body", [])
        def state = new TaskState(position, 0, [], ExecutorUsage.none())
        return StatusReport.build(context, state, position instanceof Position.AtStage ? 3 : null, LiveActivity.idle())
    }

    private static StatusReport activityReport(Activity activity) {
        def context = new TaskContext("task-1", "Title", "Body", [])
        def state = new TaskState(new Position.AtStage("implement"), 0, [], ExecutorUsage.none())
        return StatusReport.build(context, state, 3, new LiveActivity(activity, null, null))
    }

    private static StatusReport outcomeReport(Outcome outcome) {
        def context = new TaskContext("task-1", "Title", "Body", [])
        def state = new TaskState(new Position.AtStage("implement"), 0, [], ExecutorUsage.none())
        return StatusReport.build(context, state, 3, new LiveActivity(null, null, outcome))
    }

    /**
     * The deterministic sample used both by the reference-anchor spec and to
     * (re)generate {@code status-report-v1.reference.json} — built with fixed
     * {@code Instant} values (an injected-clock style sample, FR11), in the shape
     * of the spec's canonical example: non-null activity, an attempt with
     * findings, totals, lastEscalation, lastDecision.
     */
    static StatusReport referenceReport() {
        def decision = new Decision("patch in place", "plan", "operator", Instant.parse("2026-07-16T14:21:30Z"))
        def context = new TaskContext("manual-20260716-143502-x7", "Fix flaky OrderServiceSpec", "body", [decision])

        def passCheck = new CheckResult(
                new CheckRef(0, "builtin:files_exist"), new Verdict.Pass(), Duration.ofMillis(3))
        def failFinding = new Finding("command exited with 1", null, "…output tail…")
        def failCheck = new CheckResult(
                new CheckRef(1, "command:./gradlew test"), new Verdict.Fail([failFinding]), Duration.ofMillis(41250))

        def attempt = new AttemptRecord(
                1,
                AttemptRecord.Result.QUALITY_FAILURE,
                Instant.parse("2026-07-16T14:35:10Z"),
                [passCheck, failCheck],
                new ExecutorUsage(Duration.ofMillis(183000), [], null),
                JudgeUsage.none())

        def state = new TaskState(
                new Position.AtStage("implement"),
                1,
                [attempt],
                new ExecutorUsage(Duration.ofMillis(232000), [], null))

        def escalation = new EscalationReport.DecisionNeeded(
                "Refactor the retry helper or patch in place?", ["refactor", "patch"])
        def activity = new Activity.Verifying(
                new CheckRef(0, "command:./gradlew test"), Instant.parse("2026-07-16T14:41:02Z"))

        return StatusReport.build(context, state, 3, new LiveActivity(activity, escalation, null))
    }
}
