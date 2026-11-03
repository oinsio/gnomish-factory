package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The round mechanics the {@link StageAttemptLoop} delegates one round to: build the
 * {@link AttemptKey}, assemble the executor request with the prior attempts' failed check
 * results as feedback (FR4), run the {@link StageExecutor}, and shape its {@link ExecutionResult}
 * into a sealed {@link RoundOutcome} by the {@link AttemptAccounting} the {@link
 * ExecutorOutcomeClassifier} gives it — the one place a status is classified (FR7 of
 * define-executor-contract, design D10). This class switches over the accounting only:
 *
 * <ul>
 *   <li>{@link AttemptAccounting.Proceed} runs the verify chain through the {@link
 *       VerifyOrchestrator} and captures the round with its {@link Verdict} ({@link
 *       RoundOutcome.Judged}, design D4);
 *   <li>{@link AttemptAccounting.BurnAttempt} runs NO verify chain and records the round with the
 *       executor's findings as its one failed check result ({@link RoundRecords#qualityFailed});
 *   <li>{@link AttemptAccounting.EndStage} runs NO verify chain and records the round unburned
 *       with its stop ({@link RoundOutcome.Stopped}: a decision request, FR6; a reached bound);
 *   <li>{@link AttemptAccounting.Escalate} records nothing ({@link RoundOutcome.Unrecorded}).
 * </ul>
 *
 * <p>When the executor port itself throws — a failure before any round existed, or its own
 * retries exhausted — this catches the throw, logs it at ERROR, and turns it into the {@code
 * failed/infrastructure} result the classifier takes (FR10, NFR-O1). An adapter whose round died
 * reports that as a result instead, carrying the round's egress denials (FR1 of
 * fix-denial-attribution-durability; FR7 of define-executor-contract). {@link RoundFailures} writes the one operator line of a thrown and of a reported
 * failure alike. A check-adapter throw is {@link
 * VerifyOrchestrator}'s to handle. Every executed round — recorded or not, a thrown failure as
 * the {@code failed/infrastructure} result it stands for — emits {@link EngineEvent.ExecutionFinished}
 * through {@link Events#emit} BEFORE any verify chain (FR12), naming the executor that ran it and the
 * status it reached, so the round's log and ledger lines cover every run (NFR-O2, UX3 of
 * define-executor-contract). Package-private and reentrant: it holds only immutable injected collaborators (NFR-R1).
 * The {@code startedAt} instant the loop captured is threaded onto every {@link AttemptRecord}
 * (FR15 of add-manual-run, design D11). The round's denials ride the result onto the record and
 * are never consulted by a verdict (FR2, FR3 of fix-denial-report-attachment). Every recorded round
 * names itself: its task, stage and number from the {@link AttemptKey}, its round token taken once
 * from the run's {@link RoundIdentity} — never re-derived from the position or the history (FR14,
 * FR21 of define-executor-contract, design D13). An unrecorded round takes no token.
 *
 * <p>Implements FR4, FR6, FR10, FR12, FR13, NFR-O1 of add-stage-engine; FR15 of add-manual-run;
 * FR2, FR3 of fix-denial-report-attachment; FR1 of fix-denial-attribution-durability;
 * FR5 of split-logtext-leaves; FR5 of make-checkpoint-gate-durable; FR20 of
 * fix-terminal-receipt-convergence; FR7 of define-executor-contract.
 */
final class RoundExecution {

    private final StageExecutor executor;
    private final VerifyOrchestrator verifyOrchestrator;
    private final EngineEventListener listener;
    private final DescribedExecutors described;
    private final RoundIdentity rounds;

    /**
     * Wires the round's collaborators from a run's ports — the executor, the listener the {@code
     * ExecutionFinished} event is emitted to, and the run's {@link DescribedExecutors} the round's
     * executor is named from; all immutable, so this collaborator carries no mutable state (NFR-R1).
     *
     * @param ports the run's collaborators; never null
     * @param verifyOrchestrator the verify chain the round's checks run through; never null
     */
    RoundExecution(EnginePorts ports, VerifyOrchestrator verifyOrchestrator) {
        this.executor = ports.executor();
        this.verifyOrchestrator = verifyOrchestrator;
        this.listener = ports.listener();
        this.described = ports.describedExecutors();
        this.rounds = ports.roundIdentity();
    }

    /**
     * Executes one round of {@code stage} from {@code state} and shapes the result into a
     * sealed {@link RoundOutcome}. The round number is the current attempt history size (0-based)
     * and forms the {@link AttemptKey} the request and any verify chain share; the feedback is
     * the failed check results of all prior attempts (FR4). The executor runs the round, the
     * classifier gives its accounting, and the round is shaped from it. A {@link RuntimeException}
     * the executor throws is caught, logged at ERROR naming the round key, and classified as the
     * {@code failed/infrastructure} result it stands for (FR10, NFR-O1), with no denials.
     *
     * <p>Implements FR4, FR6, FR10, FR12, FR13, NFR-O1 of add-stage-engine; FR7 of
     * define-executor-contract.
     *
     * @param context the task's identity and human decisions; never null
     * @param state the recorded state this round resumes from; never null
     * @param workspace the opaque working copy the round operates on; never null
     * @param stage the resolved stage this round runs; never null
     * @param startedAt the Clock reading taken by the loop when this round began; carried onto
     *     the round's {@link AttemptRecord} verbatim (FR15 of add-manual-run, design D11); never null
     * @return the executed round shaped as a sealed outcome; never null
     */
    RoundOutcome execute(
            TaskContext context, TaskState state, Workspace workspace, StageDefinition stage, Instant startedAt) {
        int number = state.attempts().size();
        var key = new AttemptKey(context.taskId(), stage.name(), number);
        var feedback = RoundRecords.priorFailures(state);
        ExecutionResult result;
        try {
            result = executor.execute(new StageExecutor.Request(context, stage, workspace, number, feedback));
        } catch (RuntimeException ex) {
            var failed = RoundFailures.thrown(key, ex);
            return shape(context, workspace, stage, key, startedAt, failed, ExecutorOutcomeClassifier.classify(failed));
        }
        var accounting = ExecutorOutcomeClassifier.classify(result);
        RoundFailures.reported(key, accounting);
        return shape(context, workspace, stage, key, startedAt, result, accounting);
    }

    /**
     * Shapes the round from its accounting, the variant the class javadoc lists. Exhaustive
     * switch, no {@code default}. Every round emits {@link EngineEvent.ExecutionFinished} before any
     * verify chain (FR12).
     */
    private RoundOutcome shape(
            TaskContext context,
            Workspace workspace,
            StageDefinition stage,
            AttemptKey key,
            Instant startedAt,
            ExecutionResult result,
            AttemptAccounting accounting) {
        var executor = described.executorOf(stage);
        var executed = Participants.transform(executor.name(), result.usage());
        Events.emit(listener, new EngineEvent.ExecutionFinished(key, executed, executor, result.status()));
        return switch (accounting) {
            case AttemptAccounting.Proceed ignored ->
                verified(context, workspace, stage, identity(key, startedAt, executed), result);
            case AttemptAccounting.BurnAttempt burn ->
                RoundRecords.qualityFailed(identity(key, startedAt, executed), stage, result, burn);
            case AttemptAccounting.EndStage end ->
                RoundRecords.stopped(identity(key, startedAt, executed), result, end);
            case AttemptAccounting.Escalate escalate -> new RoundOutcome.Unrecorded(key, escalate);
        };
    }

    /**
     * The recorded round's identity: the key, the start instant, the token taken for it and the
     * executor's participant entry.
     */
    private RecordedRound identity(AttemptKey key, Instant startedAt, ParticipantUsage executed) {
        @Nullable RoundToken token = rounds.takeToken().orElse(null);
        return new RecordedRound(key, token, startedAt, executed);
    }

    /**
     * Runs the verify chain for a {@code completed} round with no answer, derives its overall
     * {@link Verdict} from the chain (design D4), and captures it as an {@link AttemptRecord} with
     * the executor's and the judge votes' participant entries and an explicit {@link AttemptRecord.Result} classification of that
     * verdict (FR13, design D5). The verdict is computed once and reused for both the record's
     * result and the returned {@link RoundOutcome.Judged}.
     */
    private RoundOutcome.Judged verified(
            TaskContext context,
            Workspace workspace,
            StageDefinition stage,
            RecordedRound round,
            ExecutionResult completed) {
        var key = round.key();
        var verification = verifyOrchestrator.verify(stage.verify(), context, workspace, key);
        var verdict = RoundRecords.overallVerdict(verification);
        // FR5 of make-checkpoint-gate-durable: a CannotVerify round carries its stop — the check
        // that broke the chain (its last result) with the verdict's reason and details.
        var stop = verdict instanceof Verdict.CannotVerify cv
                ? new Stop.CannotVerify(verification.results().getLast().checkRef(), cv.reason(), cv.details())
                : Stop.none();
        var record = round.record(
                RoundRecords.resultOf(verdict),
                verification.results(),
                verification.votes(),
                completed.denials(),
                stop);
        return new RoundOutcome.Judged(key, record, verdict, completed.trace());
    }
}
