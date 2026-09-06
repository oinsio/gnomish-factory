package com.github.oinsio.gnomish.status

import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Denial
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
import java.time.Duration
import java.time.Instant

/**
 * The deterministic sample behind the status-report reference documents — fixed {@link
 * Instant} values throughout (an injected-clock style sample, FR11 of add-manual-run), in
 * the shape of the spec's canonical example: non-null activity, an attempt with findings,
 * totals, lastEscalation, lastDecision.
 *
 * <p>Lives in {@code :test-fixtures}, beside {@code status-report-v1.reference.json}
 * itself, because two modules anchor against it: {@code :application}'s
 * {@code StatusReportJsonMapperSpec} (byte-identity against the reference document) and
 * {@code :adapters:git}'s {@code StatusReportEquivalenceContractSpec} (state-file render
 * equivalent to the live render). Until FR2 of fix-denial-attribution-durability the
 * second one rebuilt this sample by hand, and the two copies were kept in step by
 * memory — the shape {@code manual-sync-pairs.md} exists to forbid. Same role as
 * {@code BoardReferenceFixture}, one module up.
 *
 * <p>Also owns {@link StatusReportReferenceFixture#referenceEscalations()}: one sample per {@link EscalationReport}
 * kind, the corpus behind {@code status-report-v1.escalations.reference.jsonl}. The
 * canonical document's single {@code lastEscalation} slot pins one kind only, so the
 * other four — and any future kind — are pinned there instead.
 *
 * <p>Implements FR11, M3 of add-manual-run; FR2, M3 of fix-denial-attribution-durability.
 */
final class StatusReportReferenceFixture {

    static final String TASK_ID = 'manual-20260716-143502-x7'
    static final String TITLE = 'Fix flaky OrderServiceSpec'
    static final int ATTEMPT_LIMIT = 3

    private StatusReportReferenceFixture() {}

    /** The one decision the sample task carries, answered before the attempt ran. */
    static Decision referenceDecision() {
        new Decision('patch in place', 'plan', 'operator', Instant.parse('2026-07-16T14:21:30Z'))
    }

    /** The sample task's identity and its single answered decision. */
    static TaskContext referenceContext() {
        new TaskContext(TASK_ID, TITLE, 'body', [referenceDecision()])
    }

    /** The escalation the canonical document carries under {@code lastEscalation}. */
    static EscalationReport referenceEscalation() {
        new EscalationReport.DecisionNeeded('Refactor the retry helper or patch in place?', ['refactor', 'patch'])
    }

    /** The live-only activity: mid-verification of the sample's failing check. */
    static Activity referenceActivity() {
        new Activity.Verifying(new CheckRef(0, 'command:./gradlew test'), Instant.parse('2026-07-16T14:41:02Z'))
    }

    /** One quality-failure attempt (a passing and a failing check) plus the task totals. */
    static TaskState referenceTaskState() {
        def passCheck = new CheckResult(new CheckRef(0, 'builtin:files_exist'), new Verdict.Pass(), Duration.ofMillis(3))
        def failFinding = new Finding('command exited with 1', null, '…output tail…')
        def failCheck = new CheckResult(
                new CheckRef(1, 'command:./gradlew test'), new Verdict.Fail([failFinding]), Duration.ofMillis(41250))

        def attempt = new AttemptRecord(
                1, AttemptRecord.Result.QUALITY_FAILURE, Instant.parse('2026-07-16T14:35:10Z'),
                [passCheck, failCheck], usage(183000, 1200, 5400, 410000), JudgeUsage.none(), [])

        new TaskState(new Position.AtStage('implement'), 1, [attempt], usage(232000, 1450, 6100, 512000))
    }

    /** The whole canonical report: the state above, live activity, escalation, no outcome. */
    static StatusReport referenceReport() {
        StatusReport.build(
                referenceContext(), referenceTaskState(), ATTEMPT_LIMIT,
                new LiveActivity(referenceActivity(), referenceEscalation(), null))
    }

    /**
     * One sample per {@link EscalationReport} kind, in the order their lines are committed
     * to {@code status-report-v1.escalations.reference.jsonl}. The {@code CannotExecute}
     * sample carries a denial: its serialized form is what pins FR2's additive field, and
     * an empty list would pin nothing.
     */
    static List<EscalationReport> referenceEscalations() {
        [
            new EscalationReport.AttemptsExhausted(ATTEMPT_LIMIT),
            referenceEscalation(),
            new EscalationReport.CannotVerify(new CheckRef(0, 'external:ci'), 'timeout', 'poll exceeded 5m'),
            new EscalationReport.PipelineMismatch('removed-stage'),
            new EscalationReport.CannotExecute('round timed out after 15m', [
                Denial.unidentified(new Finding(
                        'egress denied: paste.example.com:443',
                        'paste.example.com:443/upload',
                        'kind=http method=POST'))
            ])
        ]
    }

    private static ExecutorUsage usage(long wallMillis, int input, int output, int cacheRead) {
        new ExecutorUsage(
                Duration.ofMillis(wallMillis),
                [
                    new ToolUsage('Edit', 4, Duration.ofMillis(2100))
                ],
                ['claude-sonnet-5': new TokenUsage(input, output, 30000, cacheRead)])
    }
}
