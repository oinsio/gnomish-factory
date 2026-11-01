package com.github.oinsio.gnomish.domain.engine;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The recorded metrics of one executed round within the current stage: its identity — the
 * {@code taskId}, the {@code stage} that ran it and, in a branch mode, the {@code roundToken} it
 * opened on — its {@code round} sequence number, the {@code startedAt} instant the round began, the
 * {@code checkResults} the round produced, and its {@code usage} — one {@link ParticipantUsage}
 * entry per executor invocation of the round (design D14 of define-executor-contract). FR13 records <em>every</em> executed
 * round here — including rounds that ended in {@code CannotVerify} (recorded, not
 * counted against the attempt limit) and {@code DecisionNeeded} rounds (which record no
 * checks).
 *
 * <p>The raw {@link ToolTrace} is deliberately kept OUT (design D5): it lives outside {@code
 * TaskState}, correlated to its round by {@link AttemptKey}.
 *
 * <p>The identity is written by the round's writer and never inferred by a reader (FR14 of
 * define-executor-contract, design D13): the engine stamps {@code taskId}, {@code stage} and
 * {@code round} from the round's {@link AttemptKey} and the {@code roundToken} from the run's
 * {@link com.github.oinsio.gnomish.domain.engine.port.RoundIdentity}, so a round that passed and
 * advanced the position in the same commit still names the stage that ran it. {@code taskId} and
 * {@code stage} are never blank; {@code roundToken} is the tip the round opened on in a branch mode
 * (host git, container) and {@code null} in the in-place run, which has no task branch.
 *
 * <p>{@code round} is the attempt key's number as recorded: non-negative, the round's 0-based
 * position within the current visit of the stage (it restarts on a repeated visit; see {@link
 * AttemptKey}), and the number the round commit's subject renders; its ordering within a visit is
 * the engine's concern and is not enforced here. {@code checkResults} is defensively
 * copied, unmodifiable, and MAY be empty: a {@code DecisionNeeded} round records
 * no checks, and a verdict-less execution records none either. {@code usage} is defensively
 * copied and unmodifiable: the stage's executor as a {@code transform} entry, then each judge vote
 * as a {@code verdict} entry in vote order (FR15 of define-executor-contract); an executor that
 * reported nothing is an {@code ABSENT} entry, never a missing one. Inert value data compared by
 * content.
 *
 * <p>The engine sets an explicit {@code result} (FR13, design D5): an empty {@code checkResults}
 * is ambiguous on its own, so consumers read {@code result} directly.
 *
 * <p>{@code startedAt} is the engine's {@link java.time.InstantSource}
 * reading taken when the round began — the same instant conceptually as the round's {@link
 * EngineEvent.AttemptStarted}, captured at the top of the attempt and threaded through, NOT a
 * fresh reading taken when the round is recorded or persisted (FR15, design D11). Carrying it on
 * the record (engine state) rather than only on the live event is what lets {@code
 * attempts[].startedAt} survive into the git-workflow state file and stay byte-equivalent across
 * an attempt boundary. Required non-null.
 *
 * <p>{@code denials} is the round's egress denials — what the execution environment's guard
 * blocked while the gnome worked — and is deliberately kept OUT of every verdict path (FR2 of
 * fix-denial-report-attachment): a denial is observability, not a gate, so it never feeds the
 * round's {@code result}, the stage's overall verdict, or the prior-failure feedback a retry
 * carries. That is exactly why it is a slot of its own rather than another {@code checkResults}
 * entry: a passing attempt has nowhere else to put one. Defensively copied, unmodifiable, and
 * usually empty.
 *
 * <p>{@code stop} is the escalation content the round raised, carried on the record so it is
 * durable in the same round commit as the result (design D3 of make-checkpoint-gate-durable): a
 * {@link Stop.DecisionNeeded} only on a {@code DECISION_NEEDED} round, a {@link Stop.CannotVerify}
 * only on a {@code CANNOT_VERIFY} round, a {@link Stop.BudgetExhausted} only on a {@code
 * BUDGET_EXHAUSTED} round, refused on any other result. {@link Stop.None} is valid
 * for every result — a pass and a quality failure raise no stop, and a record read from a tip
 * that predates the stop carries none (the wire field absent reads as {@code none}, FR10).
 * Required non-null.
 *
 * <p>The component checks live in {@link AttemptRecordChecks}, outside the record: PIT's record
 * filter suppresses every mutation inside a canonical constructor, and since the identity component
 * (task 5.1 of define-executor-contract) its Gregor engine crashes its minion on mutations of the
 * record's own static methods (hcoles/pitest#1285, the JVMTI Record-attribute restriction), so a
 * check kept in the record would leave the 100% gate.
 *
 * <p>Implements FR13 of add-stage-engine; FR15 of add-manual-run; FR2 of
 * fix-denial-report-attachment; FR5 of make-checkpoint-gate-durable; FR7 of
 * define-executor-contract; FR14, FR21 of define-executor-contract (the identity); FR15 of
 * define-executor-contract (the participant list).
 *
 * @param taskId the task the round belongs to; never blank
 * @param stage the stage that ran the round; never blank
 * @param roundToken the tip the round opened on, in a branch mode; {@code null} in the in-place run
 * @param round the attempt key's number: the round's 0-based sequence number within the current
 *     stage visit; never negative
 * @param result the engine's explicit classification of how the round ended; never
 *     null (FR13, design D5)
 * @param startedAt the Clock reading taken when the round began; never null (FR15, design D11)
 * @param checkResults the verify results produced this round; defensively copied,
 *     unmodifiable, possibly empty
 * @param usage the round's participant entries, one per executor invocation; defensively copied,
 *     unmodifiable (FR15 of define-executor-contract)
 * @param denials the egress denials the round's environment recorded; defensively copied,
 *     unmodifiable, possibly empty; never an input to any verdict
 * @param stop the stop the round raised; never null, {@link Stop.None} when it raised none, and
 *     otherwise of the kind its {@code result} names (FR5 of make-checkpoint-gate-durable)
 */
public record AttemptRecord(
        String taskId,
        String stage,
        @Nullable RoundToken roundToken,
        int round,
        Result result,
        Instant startedAt,
        List<CheckResult> checkResults,
        List<ParticipantUsage> usage,
        List<Denial> denials,
        Stop stop) {

    /**
     * How a recorded round ended, set explicitly by the engine when it records the
     * round (FR13, design D5): {@link #PASSED} — verification passed; {@link
     * #QUALITY_FAILURE} — an explicit non-pass verdict (burns an attempt); {@link
     * #CANNOT_VERIFY} — a verdict could not be reached (recorded, not counted); {@link
     * #DECISION_NEEDED} — the executor asked a human before any check ran; {@link
     * #BUDGET_EXHAUSTED} — the executor reported {@code failed/limit}, a bound of the stage was
     * reached (recorded with its usage, not counted; FR7 of define-executor-contract). Carried on the
     * record so consumers read the classification directly rather than inferring it from
     * an (ambiguous) empty check list.
     */
    public enum Result {
        PASSED,
        QUALITY_FAILURE,
        CANNOT_VERIFY,
        DECISION_NEEDED,
        BUDGET_EXHAUSTED
    }

    public AttemptRecord {
        taskId = AttemptRecordChecks.requireNonBlank(taskId, "taskId");
        stage = AttemptRecordChecks.requireNonBlank(stage, "stage");
        round = AttemptRecordChecks.requireNonNegative(round, "round");
        startedAt = AttemptRecordChecks.requireNonNull(startedAt, "startedAt");
        checkResults = List.copyOf(checkResults);
        usage = List.copyOf(usage);
        denials = List.copyOf(denials);
        stop = AttemptRecordChecks.requireStopOfResult(result, stop);
    }
}
