package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Denial
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.ExecutorRole
import com.github.oinsio.gnomish.domain.engine.ExecutorVersions
import com.github.oinsio.gnomish.domain.engine.Finding
import com.github.oinsio.gnomish.domain.engine.MismatchCause
import com.github.oinsio.gnomish.domain.engine.ModelUsage
import com.github.oinsio.gnomish.domain.engine.ParticipantUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolUsage
import com.github.oinsio.gnomish.domain.engine.UsageCost
import com.github.oinsio.gnomish.domain.engine.UsageProvenance
import com.github.oinsio.gnomish.domain.engine.UsageSnapshot
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Duration
import java.time.Instant

/**
 * The deterministic sample behind the status-report reference documents — fixed {@link
 * Instant} values throughout (an injected-clock style sample, FR11 of add-manual-run), in
 * the shape of the spec's canonical example: an attempt with findings, totals,
 * lastEscalation, lastDecision.
 *
 * <p>Lives in {@code :test-fixtures}, beside {@code status-report-v1.reference.json}
 * itself, because two modules anchor against it: {@code :application}'s
 * {@code StatusReportJsonMapperSpec} (byte-identity against the reference document) and
 * {@code :adapters:git}'s {@code StatusReportEquivalenceContractSpec} (the render of the
 * persisted files equivalent to the render of the in-memory report). Until FR2 of fix-denial-attribution-durability the
 * second one rebuilt this sample by hand, and the two copies were kept in step by
 * memory — the shape {@code manual-sync-pairs.md} exists to forbid. Same role as
 * {@code BoardReferenceFixture}, one module up.
 *
 * <p>Also owns {@link StatusReportReferenceFixture#referenceEscalations()}: one sample per {@link EscalationReport}
 * kind, the corpus behind {@code status-report-v1.escalations.reference.jsonl}. The
 * canonical document's single {@code lastEscalation} slot pins one kind only, so the
 * other four — and any future kind — are pinned there instead.
 *
 * <p>Implements FR11, M3 of add-manual-run; FR2, M3 of fix-denial-attribution-durability; FR6
 * of make-run-headless.
 */
final class StatusReportReferenceFixture {

    static final String TASK_ID = 'manual-20260716-143502-x7'
    static final String TITLE = 'Fix flaky OrderServiceSpec'

    private StatusReportReferenceFixture() {}

    /** The one decision the sample task carries, answered before the attempt ran. */
    static Decision referenceDecision() {
        new Decision('patch in place', 'plan', 'operator', Instant.parse('2026-07-16T14:21:30Z'))
    }

    /** The sample task's identity and its single answered decision. */
    static TaskContext referenceContext() {
        new TaskContext(TASK_ID, UntrustedText.tracker(TITLE), UntrustedText.tracker('body'), [referenceDecision()])
    }

    /** The escalation the canonical document carries under {@code lastEscalation}. */
    static EscalationReport referenceEscalation() {
        new EscalationReport.DecisionNeeded(UntrustedText.agent('Refactor the retry helper or patch in place?'), [
            UntrustedText.agent('refactor'),
            UntrustedText.agent('patch')
        ])
    }

    /** One quality-failure attempt (a passing and a failing check) plus the task totals. */
    static TaskState referenceTaskState() {
        def passCheck = new CheckResult(new CheckRef(0, UntrustedText.manifest('builtin:files_exist')), new Verdict.Pass(), Duration.ofMillis(3))
        def failFinding = new Finding('command exited with 1', null, '…output tail…')
        def failCheck = new CheckResult(
                new CheckRef(1, UntrustedText.manifest('command:./gradlew test')), new Verdict.Fail([failFinding]), Duration.ofMillis(41250))

        def attempt = AttemptRecords.identified(
                TASK_ID, 'implement', RoundToken.of('9f1c2b7a4e6d8c0b1a2f3e4d5c6b7a8f9e0d1c2b'),
                1, AttemptRecord.Result.QUALITY_FAILURE, Instant.parse('2026-07-16T14:35:10Z'),
                [passCheck, failCheck], [executed()], [], Stop.none())

        new TaskState(new Position.AtStage('implement'), 1, [attempt], referenceTotals(), ExecutorVersions.none())
    }

    /** The whole canonical report: the state above, the recorded escalation, no outcome. */
    static StatusReport referenceReport() {
        StatusReport.build(referenceContext(), referenceTaskState(), referenceEscalation(), null)
    }

    /**
     * One sample per {@link EscalationReport} kind — and one per {@link MismatchCause} of a {@code
     * PipelineMismatch} (FR13 of define-executor-contract) — in the order their lines are committed
     * to {@code status-report-v1.escalations.reference.jsonl}. The {@code CannotExecute}
     * sample carries a denial: its serialized form is what pins FR2's additive field, and
     * an empty list would pin nothing.
     */
    static List<EscalationReport> referenceEscalations() {
        [
            new EscalationReport.AttemptsExhausted(3),
            referenceEscalation(),
            new EscalationReport.CannotVerify(new CheckRef(0, UntrustedText.manifest('external:ci')), UntrustedText.subprocess('timeout'), UntrustedText.subprocess('poll exceeded 5m')),
            new EscalationReport.PipelineMismatch(new MismatchCause.StaleStage(UntrustedText.branchDocument('removed-stage'))),
            new EscalationReport.PipelineMismatch(new MismatchCause.ExecutorVersion(
                    UntrustedText.manifest('acme'), UntrustedText.branchDocument('1.4.0'), UntrustedText.subprocess('2.0.0'))),
            new EscalationReport.CannotExecute(UntrustedText.subprocess('round timed out after 15m'), [
                Denial.unidentified(new Finding(
                        'egress denied: paste.example.com:443',
                        'paste.example.com:443/upload',
                        'kind=http method=POST'))
            ]),
            new EscalationReport.BudgetExhausted(UntrustedText.agent('token bound 200000 reached'))
        ]
    }

    /**
     * The canonical attempt's one participant: the stage's executor, reported, priced — the entry
     * the spec's canonical example shows (FR15 of define-executor-contract).
     */
    private static ParticipantUsage executed() {
        new ParticipantUsage(ExecutorRole.TRANSFORM, 'agent-cli', null, UsageProvenance.REPORTED, Duration.ofMillis(183000),
                [
                    new ToolUsage('Edit', 4, Duration.ofMillis(2100))
                ],
                ['claude-sonnet-5': new ModelUsage(new TokenUsage(1200, 5400, 30000, 410000), new BigDecimal('0.1607'))])
    }

    /**
     * The task's snapshot after two rounds of {@code implement}, both priced: the totals the spec's
     * canonical example shows (FR17 of define-executor-contract).
     */
    private static UsageSnapshot referenceTotals() {
        new UsageSnapshot(
                [(ExecutorRole.TRANSFORM): ['claude-sonnet-5': new TokenUsage(1450, 6100, 30000, 512000)]],
                new UsageCost(new BigDecimal('0.1922'), [:], true),
                2,
                RoundToken.of('9f1c2b7a4e6d8c0b1a2f3e4d5c6b7a8f9e0d1c2b'))
    }
}
