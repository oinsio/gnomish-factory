package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.RoundIdentity;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.time.Instant;
import java.util.List;

/**
 * The round mechanics the {@link StageAttemptLoop} delegates one round to: build the
 * {@link AttemptKey}, assemble the executor request with the prior attempts' failed check
 * results as feedback (FR4), run the round's requests through {@link TransformRound} — its {@code
 * start}, and its polls when it went {@code pending} on an executor declaring {@code poll} (design D11
 * of extract-agent-round-runner) — and shape the {@link ExecutionResult} it ended on
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
 * <p>A throw of the executor port is caught, logged at ERROR and turned into the {@code
 * failed/infrastructure} result the classifier takes (FR10, NFR-O1); an adapter whose round died
 * reports a result instead, with the round's egress denials (FR1 of fix-denial-attribution-durability;
 * FR7 of define-executor-contract). {@link RoundFailures} writes the one operator line of a thrown and
 * of a reported failure alike; a check-adapter throw is {@link VerifyOrchestrator}'s. Every executed round — recorded or not, a thrown failure as
 * the {@code failed/infrastructure} result it stands for — emits {@link EngineEvent.ExecutionFinished}
 * through {@link Events#emit} BEFORE any verify chain (FR12), naming the executor that ran it and the
 * status it reached, so the round's log and ledger lines cover every run (NFR-O2, UX3 of
 * define-executor-contract). Reentrant: it holds only immutable collaborators (NFR-R1). The {@code startedAt} instant the loop captured is threaded onto every {@link AttemptRecord}
 * (FR15 of add-manual-run, design D11). The round's denials ride the result onto the record and
 * are never consulted by a verdict (FR2, FR3 of fix-denial-report-attachment). Every recorded round
 * names itself: its task, stage and number from the {@link AttemptKey}, its round token taken once
 * from the run's {@link RoundIdentity} — never re-derived from the position or the history (FR14,
 * FR21 of define-executor-contract, design D13), through the round's one {@link RoundTokenTake}, so
 * a pending round's entry and its record carry one token. An unrecorded round that never went
 * pending takes no token. The verify chain gets that same token with the state the round ran from,
 * so a pending entry names its round by it (design D21 of migrate-verify-to-executor-contract).
 *
 * <p>Implements FR4, FR6, FR10, FR12, FR13, NFR-O1 of add-stage-engine; FR15 of add-manual-run;
 * FR2, FR3 of fix-denial-report-attachment; FR1 of fix-denial-attribution-durability;
 * FR5 of split-logtext-leaves; FR5 of make-checkpoint-gate-durable; FR20 of
 * fix-terminal-receipt-convergence; FR7 of define-executor-contract; FR20 of
 * migrate-verify-to-executor-contract; FR10, NFR-O2, UX3 of extract-agent-round-runner.
 */
final class RoundExecution {

    private final TransformRound transform;
    private final VerifyOrchestrator verifyOrchestrator;
    private final EngineEventListener listener;
    private final DescribedExecutors described;
    private final RoundIdentity rounds;

    /**
     * Wires the round's collaborators from a run's ports — the executor and the pending poll its
     * {@link TransformRound} runs the round's requests through, the listener the {@code
     * ExecutionFinished} event is emitted to, and the run's {@link DescribedExecutors} the round's
     * executor is named from; all immutable, so this collaborator carries no mutable state (NFR-R1).
     *
     * @param ports the run's collaborators; never null
     * @param verifyOrchestrator the verify chain the round's checks run through; never null
     * @param poll the engine's one owner of pending work, a pending round's polls go through; never null
     */
    RoundExecution(EnginePorts ports, VerifyOrchestrator verifyOrchestrator, PendingPoll poll) {
        this.transform = new TransformRound(ports, poll);
        this.verifyOrchestrator = verifyOrchestrator;
        this.listener = ports.listener();
        this.described = ports.describedExecutors();
        this.rounds = ports.roundIdentity();
    }

    /**
     * Executes one round of {@code stage} from {@code state} and shapes the result it ended on into a
     * sealed {@link RoundOutcome}. The round number is the size (0-based) of the stage's own history
     * in the {@link Engine#resumedFrom} view; the feedback is the failed check results of all prior
     * attempts (FR4). A {@link RuntimeException} the requests throw is caught, logged at ERROR naming
     * the round key, and classified as the {@code failed/infrastructure} result it stands for (FR10,
     * NFR-O1), with no denials. {@code state} is the last persisted state: the round's and the verify
     * chain's pending writes carry it unchanged but for their entries (D21 item 12 of
     * migrate-verify-to-executor-contract).
     *
     * <p>Implements FR4, FR6, FR10, FR12, FR13, NFR-O1 of add-stage-engine; FR7 of
     * define-executor-contract; FR10 of extract-agent-round-runner.
     *
     * @param context the task's identity and human decisions; never null
     * @param state the last persisted state this round resumes from; never null
     * @param workspace the opaque working copy the round operates on; never null
     * @param stage the resolved stage this round runs; never null
     * @param startedAt the Clock reading taken by the loop when this round began; carried onto
     *     the round's {@link AttemptRecord} verbatim (FR15 of add-manual-run, design D11); never null
     * @return the executed round shaped as a sealed outcome; never null
     */
    RoundOutcome execute(
            TaskContext context, TaskState state, Workspace workspace, StageDefinition stage, Instant startedAt) {
        var key = new AttemptKey(
                context.taskId(),
                stage.name(),
                Engine.resumedFrom(state).attempts().size());
        var feedback = RoundRecords.priorFailures(Engine.resumedFrom(state));
        var token = new RoundTokenTake(rounds);
        PendingRun run;
        try {
            run = transform.run(
                    new StageExecutor.Request(context, stage, workspace, key.attempt(), feedback), key, state, token);
        } catch (RuntimeException ex) {
            var failed = RoundFailures.thrown(key, ex);
            var thrown = new PendingRun(failed, null, null, List.of(failed.usage()));
            var round = new Round(key, startedAt, token, ExecutorOutcomeClassifier.classify(failed));
            return shape(context, state, workspace, stage, round, thrown);
        }
        var accounting = ExecutorOutcomeClassifier.classify(run.result());
        RoundFailures.reported(key, accounting);
        return shape(context, state, workspace, stage, new Round(key, startedAt, token, accounting), run);
    }

    /**
     * The round being shaped: its key, its start instant, its one token take and the accounting the
     * classifier gave the result it ended on.
     */
    private record Round(AttemptKey key, Instant startedAt, RoundTokenTake token, AttemptAccounting accounting) {}

    /**
     * Shapes the round from its accounting, the variant the class javadoc lists. Exhaustive
     * switch, no {@code default}. Every round emits {@link EngineEvent.ExecutionFinished} before any
     * verify chain (FR12), naming the key a pending round went pending with or was resumed by (UX3 of
     * extract-agent-round-runner); its record carries one {@code transform} entry per request
     * (NFR-O2 of extract-agent-round-runner).
     */
    private RoundOutcome shape(
            TaskContext context,
            TaskState state,
            Workspace workspace,
            StageDefinition stage,
            Round round,
            PendingRun run) {
        var key = round.key();
        var executor = described.executorOf(stage);
        var executed = run.usages().stream()
                .map(usage -> Participants.transform(executor.name(), usage))
                .toList();
        var result = run.result();
        Events.emit(
                listener,
                new EngineEvent.ExecutionFinished(
                        key, executed.getLast(), executor, result.status(), run.pendingKey(), run.resumedKey()));
        return switch (round.accounting()) {
            case AttemptAccounting.Proceed ignored ->
                verified(context, state, workspace, stage, identity(round, executed), result);
            case AttemptAccounting.BurnAttempt burn ->
                RoundRecords.qualityFailed(identity(round, executed), stage, result, burn);
            case AttemptAccounting.EndStage end -> RoundRecords.stopped(identity(round, executed), result, end);
            case AttemptAccounting.Escalate escalate -> new RoundOutcome.Unrecorded(key, escalate);
        };
    }

    /** The recorded round's identity: key, start instant, the one take's token, executor entries. */
    private static RecordedRound identity(Round round, List<ParticipantUsage> executed) {
        return new RecordedRound(round.key(), round.token().token(), round.startedAt(), executed);
    }

    /**
     * Runs the verify chain for a {@code completed} round with no answer, derives its overall
     * {@link Verdict} from the chain (design D4), and captures it as an {@link AttemptRecord} with the
     * executor's and the judge votes' participant entries and the verdict's {@link
     * AttemptRecord.Result} (FR13, design D5); the verdict is computed once for both.
     */
    private RoundOutcome.Judged verified(
            TaskContext context,
            TaskState state,
            Workspace workspace,
            StageDefinition stage,
            RecordedRound round,
            ExecutionResult completed) {
        var key = round.key();
        var verification = verifyOrchestrator.verify(stage, context, workspace, key, state, round.token());
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
