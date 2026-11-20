package com.github.oinsio.gnomish.status.json

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.MismatchCause
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.UsageCost
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.executorcontract.usagewire.UsageCostDto
import com.github.oinsio.gnomish.executorcontract.usagewire.UsageSnapshotDto
import com.github.oinsio.gnomish.status.Outcome
import com.github.oinsio.gnomish.status.StatusReport
import com.github.oinsio.gnomish.status.StatusReportReferenceFixture
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import com.github.oinsio.gnomish.usage.wire.UsageSnapshotWire
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

    // FR5, NFR-C1, D12 of add-agent-executor; FR17 of define-executor-contract: reference anchor
    // covers the spec's canonical example shape, including a priced participant entry and the
    // usage snapshot (the empty snapshot of an unreported run is covered separately)
    // Scenario: observability/status-report / Canonical document
    // Scenario: observability/status-report / The task and position sections
    // Scenario: observability/status-report / An attempt carries its round record
    // Scenario: observability/status-report / Totals carry the task's executor usage
    // Scenario: observability/status-report / Contract drift caught
    // Scenario: observability/status-report / Pre-release amendment stays v1
    def "reference anchor: serializing the deterministic sample is byte-identical to status-report-v1.reference.json"() {
        given:
        def referenceText = getClass().getResourceAsStream('/status-report-v1.reference.json').getText('UTF-8')

        expect:
        mapper.serialize(referenceReport()) == referenceText
    }

    // FR2, M3 of fix-denial-attribution-durability: the canonical document's single
    //     lastEscalation slot can pin exactly one kind, so every kind gets a compact line
    //     of its own here — including the denials this change adds to cannotExecute, whose
    //     serialized form has no other place to be pinned byte-exactly.
    // Scenario: observability/status-report / Every escalation kind is pinned byte-exactly
    def "reference anchor: each escalation kind is byte-identical to its line in status-report-v1.escalations.reference.jsonl"() {
        given:
        def referenceLines = corpusLines()
        def json = StatusJson.mapper()

        expect: "one committed line per sample, in the fixture's order"
        StatusReportReferenceFixture.referenceEscalations().eachWithIndex { escalation, index ->
            assert json.writeValueAsString(EscalationMapper.toDto(escalation)) == referenceLines[index]
        }
    }

    // FR2 of fix-denial-attribution-durability: completeness by construction — a sixth
    //     EscalationReport variant fails here until it has a committed line, so a new kind
    //     cannot ship with its wire form unpinned (the rule testing.md states for wire
    //     vocabularies: iterate the variants, never a hand-listed subset).
    // Scenario: observability/status-report / A new escalation kind cannot ship unpinned
    // FR13 of define-executor-contract: each MismatchCause of a PipelineMismatch is its own line too
    def "the escalation corpus pins every EscalationReport kind"() {
        given:
        def kinds = EscalationReport.permittedSubclasses.length + MismatchCause.permittedSubclasses.length - 1
        def causes = StatusReportReferenceFixture.referenceEscalations()
                .findAll { it instanceof EscalationReport.PipelineMismatch }
                .collect {
                    (it as EscalationReport.PipelineMismatch).cause().class
                } as Set

        expect:
        StatusReportReferenceFixture.referenceEscalations().size() == kinds
        corpusLines().length == kinds
        causes == MismatchCause.permittedSubclasses as Set
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

    // FR12 of make-checkpoint-gate-durable: a gate is its own position token, and currentStage
    //     describes the stage that passed rather than being withdrawn as at pipelineEnd
    // Scenario: observability/status-report / A gate renders as awaiting approval
    def "position renders awaitingApproval with the gate's stage"() {
        given:
        def report = idleReport(new Position.AwaitingApproval("release"))

        expect:
        mapper.toDto(report).position() == new PositionDto.AwaitingApproval("awaitingApproval", "release")
        mapper.toDto(report).currentStage() != null
        mapper.serialize(report).contains('"type" : "awaitingApproval"')
    }

    // FR6 of make-run-headless: the live-only fields are withdrawn from contract v1 — no
    //     document carries an activity section, and currentStage carries attemptsUsed and
    //     attempts alone (the "Withdrawn live fields are absent" scenario)
    // Scenario: observability/status-report / Withdrawn live fields are absent
    def "FR6: no document carries an activity key or a currentStage attemptLimit"() {
        given:
        def tree = StatusJson.mapper().readTree(mapper.serialize(report))

        expect:
        !tree.has("activity")
        tree.get("currentStage").isNull() || tree.get("currentStage").fieldNames().toList() == ["attemptsUsed", "attempts"]

        where:
        report << [
            referenceReport(),
            idleReport(new Position.AtStage("implement")),
            idleReport(new Position.PipelineEnd()),
            outcomeReport(new Outcome.Completed())
        ]
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
        def report = outcomeReport(new Outcome.Aborted(failedAt, UntrustedText.subprocess("disk full")))

        expect:
        mapper.toDto(report).outcome() == new OutcomeDto.Aborted("aborted", failedAt.toString(), "disk full")
    }

    def "verdict pass renders empty findings and no reason/details"() {
        given:
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest("builtin:files_exist")), new Verdict.Pass(null, []), Duration.ofMillis(3))

        expect:
        AttemptMapper.toCheck(check) ==
                new CheckDto("builtin:files_exist", "pass", [], 3L, null, null, null)
    }

    // FR7 of migrate-verify-to-executor-contract (D5 as amended): a pass's below-threshold findings render as a failure's do
    def "verdict pass renders its findings in full"() {
        given:
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest("builtin:lint")),
                new Verdict.Pass("https://ci.example/runs/8", [
                    new Finding("unused import", "A.java:3", null),
                    new Finding("long line", null, "121 > 120")
                ]), Duration.ofMillis(5))

        expect:
        AttemptMapper.toCheck(check) ==
                new CheckDto("builtin:lint", "pass", [
                    new FindingDto("unused import", "A.java:3", null),
                    new FindingDto("long line", null, "121 > 120")
                ], 5L, null, null, "https://ci.example/runs/8")
    }

    // Scenario: observability/status-report / Findings are carried in full
    def "verdict fail renders findings in full"() {
        given:
        def finding = new Finding("command exited with 1", null, "…output tail…")
        def check = new CheckResult(
                new CheckRef(1, UntrustedText.manifest("command:./gradlew test")), new Verdict.Fail([finding]), Duration.ofMillis(41250))

        expect:
        AttemptMapper.toCheck(check) ==
                new CheckDto("command:./gradlew test", "fail",
                [
                    new FindingDto("command exited with 1", null, "…output tail…")
                ], 41250L, null, null, null)
    }

    def "verdict cannotVerify surfaces reason and details, empty findings"() {
        given:
        def check = new CheckResult(
                new CheckRef(0, UntrustedText.manifest("external:ci")), new Verdict.CannotVerify(UntrustedText.subprocess("timeout"), UntrustedText.subprocess("poll exceeded 5m")),
                Duration.ofMillis(300000))

        expect:
        AttemptMapper.toCheck(check) ==
                new CheckDto("external:ci", "cannotVerify", [], 300000L, "timeout", "poll exceeded 5m", null)
    }

    def "escalation attemptsExhausted renders limit, stage and at are null"() {
        expect:
        EscalationMapper.toDto(new EscalationReport.AttemptsExhausted(3)) ==
                new EscalationDto.AttemptsExhausted("attemptsExhausted", null, null, 3)
    }

    // Scenario: observability/status-report / The last escalation and the last decision
    def "escalation decisionNeeded renders question and options"() {
        expect:
        EscalationMapper.toDto(new EscalationReport.DecisionNeeded(UntrustedText.agent("Refactor or patch?"), [
            UntrustedText.agent("refactor"),
            UntrustedText.agent("patch")
        ])) ==
        new EscalationDto.DecisionNeeded("decisionNeeded", null, null, "Refactor or patch?", ["refactor", "patch"])
    }

    def "escalation cannotVerify renders check label, reason, details"() {
        given:
        def checkRef = new CheckRef(0, UntrustedText.manifest("external:ci"))

        expect:
        EscalationMapper.toDto(new EscalationReport.CannotVerify(checkRef, UntrustedText.subprocess("timeout"), UntrustedText.subprocess("detail"))) ==
                new EscalationDto.CannotVerify("cannotVerify", null, null, "external:ci", "timeout", "detail")
    }

    def "escalation pipelineMismatch renders staleStage"() {
        expect:
        EscalationMapper.toDto(new EscalationReport.PipelineMismatch(new MismatchCause.StaleStage(UntrustedText.branchDocument("removed-stage")))) ==
                new EscalationDto.PipelineMismatch("pipelineMismatch", null, null, "removed-stage", null)
    }

    def "escalation cannotExecute renders cause"() {
        expect:
        EscalationMapper.toDto(new EscalationReport.CannotExecute(UntrustedText.subprocess("adapter crashed"), [])) ==
        new EscalationDto.CannotExecute("cannotExecute", null, null, "adapter crashed", [])
    }

    // FR7 of define-executor-contract: the sixth kind carries the bound
    // Scenario: observability/status-report / The last escalation and the last decision
    def "escalation budgetExhausted renders the bound"() {
        expect:
        EscalationMapper.toDto(new EscalationReport.BudgetExhausted(UntrustedText.agent("token bound 1000"))) ==
                new EscalationDto.BudgetExhausted("budgetExhausted", null, null, "token bound 1000")
    }

    // FR2, NFR-S1 of fix-denial-attribution-durability: the round that could not execute
    //     left no attempt record, so its denials ride the escalation — through the same
    //     finding shape a check's findings use, structured metadata only.
    // Scenario: observability/status-report / The last escalation and the last decision
    def "escalation cannotExecute carries the denials of the round that could not execute"() {
        given:
        def denial = new Finding(
                "egress denied: paste.example.com:443", "paste.example.com:443/upload", "kind=http method=POST")

        expect:
        EscalationMapper.toDto(new EscalationReport.CannotExecute(UntrustedText.subprocess("round timed out"), [Denial.unidentified(denial)])) ==
        new EscalationDto.CannotExecute("cannotExecute", null, null, "round timed out", [
            new FindingDto("egress denied: paste.example.com:443", "paste.example.com:443/upload", "kind=http method=POST")
        ])
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
        expect:
        mapper.toDto(referenceReport()).currentStage().attempts()[0].usage()[0].wallMillis() == 183000L
    }

    def "instants render as ISO-8601 UTC strings"() {
        expect:
        mapper.toDto(referenceReport()).lastDecision().at() == "2026-07-16T14:21:30Z"
    }

    def "human-only run: totals carry the empty snapshot"() {
        given:
        def report = idleReport(new Position.AtStage("implement"))

        when:
        def dto = mapper.toDto(report)

        then:
        dto.totals() == new UsageSnapshotDto([:], new UsageCostDto(BigDecimal.ZERO, [:], true), 0, null)
    }

    // FR17 of define-executor-contract: the status document writes the tip's snapshot as recorded,
    //     every component, through the one snapshot mapping
    def "totals are the recorded snapshot, every component written"() {
        given:
        def context = new TaskContext("task-1", UntrustedText.tracker("Title"), UntrustedText.tracker("Body"), [])
        def snapshot = new UsageSnapshot(
                [(ExecutorRole.VERDICT): ['j': new TokenUsage(1, 2, 3, 4)]],
                new UsageCost(new BigDecimal('0.5'), [(ExecutorRole.VERDICT): ['j': new TokenUsage(1, 2, 3, 4)]], false),
                4,
                RoundToken.of('9f1c2b7a4e6d8c0b1a2f3e4d5c6b7a8f9e0d1c2b'))
        def state = new TaskState(new Position.AtStage("implement"), 0, [], snapshot, ExecutorVersions.none())

        expect:
        mapper.toDto(StatusReport.build(context, state, null, null)).totals() == UsageSnapshotWire.toDto(snapshot)
        UsageSnapshotWire.fromDto(mapper.toDto(StatusReport.build(context, state, null, null)).totals()) == snapshot
    }

    private static StatusReport idleReport(Position position) {
        def context = new TaskContext("task-1", UntrustedText.tracker("Title"), UntrustedText.tracker("Body"), [])
        def state = new TaskState(position, 0, [], UsageSnapshot.EMPTY, ExecutorVersions.none())
        return StatusReport.build(context, state, null, null)
    }

    // FR4, NFR-O1, NFR-S1, M1 of fix-denial-report-attachment: the whole point of the change —
    //     a blocked exfiltration attempt during an OTHERWISE PASSING attempt is visible to the
    //     reviewer, and visible as a finding rather than as a changed outcome
    // Scenario: observability/status-report / Passing attempt shows its denial
    // Scenario: sandbox/sandbox-egress / Denied exfiltration attempt reaches the report on a passing attempt
    def "M1: a passing attempt shows its denial while staying passed"() {
        given: 'a round that passed every check and denied one egress attempt'
        def denial = new Finding(
                "egress denied: paste.example.com:443", "paste.example.com:443/upload", "kind=http method=POST")
        def check = new CheckResult(new CheckRef(0, UntrustedText.manifest("builtin:files_exist")), new Verdict.Pass(null, []), Duration.ofMillis(3))
        def attempt = AttemptRecords.of(
                0, AttemptRecord.Result.PASSED, Instant.parse("2026-07-16T14:35:10Z"),
                [check], ExecutorUsage.none(), [Denial.unidentified(denial)], Stop.none())

        when:
        def dto = mapper.toDto(reportOf(attempt)).currentStage().attempts()[0]

        then: 'the attempt is passed — a denial gates nothing (proposal NG3)'
        dto.result() == "passed"

        and: 'and it carries the denial in the check-finding shape: host, path, method, no body (NFR-S1)'
        dto.denials() == [
            new FindingDto(
            "egress denied: paste.example.com:443", "paste.example.com:443/upload", "kind=http method=POST")
        ]
    }

    // UX2: a task that never denied reports an empty array, not a fabricated or omitted one
    // Scenario: observability/status-report / Quiet task reports no noise
    def "UX2: an attempt with no denials renders an empty array"() {
        given:
        def attempt = AttemptRecords.of(
                0, AttemptRecord.Result.PASSED, Instant.parse("2026-07-16T14:35:10Z"),
                [], ExecutorUsage.none(), [], Stop.none())

        expect:
        mapper.toDto(reportOf(attempt)).currentStage().attempts()[0].denials() == []
    }

    private static StatusReport reportOf(AttemptRecord attempt) {
        def context = new TaskContext("task-1", UntrustedText.tracker("Title"), UntrustedText.tracker("Body"), [])
        def state = new TaskState(new Position.AtStage("implement"), 1, [attempt], UsageSnapshot.EMPTY, ExecutorVersions.none())
        return StatusReport.build(context, state, null, null)
    }

    private static StatusReport outcomeReport(Outcome outcome) {
        def context = new TaskContext("task-1", UntrustedText.tracker("Title"), UntrustedText.tracker("Body"), [])
        def state = new TaskState(new Position.AtStage("implement"), 0, [], UsageSnapshot.EMPTY, ExecutorVersions.none())
        return StatusReport.build(context, state, null, outcome)
    }

    private static String[] corpusLines() {
        StatusReportJsonMapperSpec.getResourceAsStream('/status-report-v1.escalations.reference.jsonl')
                .getText('UTF-8').split('\n')
    }

    /**
     * The deterministic sample, owned by {@link StatusReportReferenceFixture} in {@code
     * :test-fixtures} so this spec and {@code StatusReportEquivalenceContractSpec} anchor
     * against one sample rather than two hand-synced copies (FR2 of
     * fix-denial-attribution-durability). Regenerating {@code
     * status-report-v1.reference.json} means editing that fixture and pasting what this
     * spec's anchor feature then reports as the actual value.
     */
    private static StatusReport referenceReport() {
        StatusReportReferenceFixture.referenceReport()
    }
}
