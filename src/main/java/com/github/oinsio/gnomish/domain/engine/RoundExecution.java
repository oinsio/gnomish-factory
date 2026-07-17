package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The round mechanics the {@link StageAttemptLoop} delegates one round to: build the
 * {@link AttemptKey}, assemble the executor request with the prior attempts' failed check
 * results as feedback (FR4), run the {@link StageExecutor}, and shape the result into a
 * sealed {@link RoundOutcome} the loop routes. A {@link ExecutionResult.Completed} runs the verify
 * chain through the {@link VerifyOrchestrator} and captures the round with its {@link Verdict}
 * ({@link RoundOutcome.Verified}, design D4); a {@link ExecutionResult.DecisionNeeded} runs NO verify
 * chain — the executor asked a human — recording the round with no check results ({@link
 * RoundOutcome.NeedsDecision}, FR6, FR13, design D6).
 *
 * <p>When the executor port itself throws — its own retries exhausted — the round never
 * completes: this catches the throw, logs it at ERROR, and shapes it into {@link
 * RoundOutcome.CannotExecute}, no round recorded and no verify chain run (FR10, NFR-O1); a
 * check-adapter throw, by contrast, is {@link VerifyOrchestrator}'s to handle. A successful
 * executor return — either variant, both carrying {@code usage()} — emits {@link
 * EngineEvent.ExecutionFinished} through the shared {@link Events#emit} helper BEFORE the verify
 * chain, the per-round "gnome done, verifying" signal (FR12, the choreography diagram's
 * AttemptStarted → ExecutionFinished → checks order); a throw is caught first, so a {@link
 * Outcome.CannotExecute} round emits none. Package-private and reentrant: it holds only its
 * immutable injected collaborators (plus a static logger) and no mutable state, so one instance
 * drives concurrent rounds safely (NFR-R1). The {@code startedAt} instant the loop captured when
 * the round began is threaded onto every {@link AttemptRecord} this builds (FR15 of add-manual-run,
 * design D11).
 *
 * <p>Implements FR4, FR6, FR10, FR12, FR13, NFR-O1 of add-stage-engine; FR15 of add-manual-run.
 */
final class RoundExecution {

    private static final Logger log = LoggerFactory.getLogger(RoundExecution.class);

    private final StageExecutor executor;
    private final VerifyOrchestrator verifyOrchestrator;
    private final EngineEventListener listener;

    /**
     * Wires the round's collaborators from a run's ports; all immutable, so this collaborator
     * carries no mutable state (NFR-R1).
     *
     * @param executor the port that runs one round of the stage's work; never null
     * @param verifyOrchestrator the verify chain the round's checks run through; never null
     * @param listener the observer the {@code ExecutionFinished} event is emitted to; never null
     */
    RoundExecution(StageExecutor executor, VerifyOrchestrator verifyOrchestrator, EngineEventListener listener) {
        this.executor = executor;
        this.verifyOrchestrator = verifyOrchestrator;
        this.listener = listener;
    }

    /**
     * Executes one round of {@code stage} from {@code state} and shapes the result into a
     * sealed {@link Outcome}. The round number is the current attempt history size (0-based)
     * and forms the {@link AttemptKey} the request and any verify chain share; the feedback is
     * the failed check results of all prior attempts (FR4). The executor runs the round, then
     * an exhaustive switch (no {@code default}) builds the variant: {@link
     * ExecutionResult.Completed} verifies to {@link Outcome.Verified}, {@link
     * ExecutionResult.DecisionNeeded} skips verification to {@link Outcome.NeedsDecision}. A
     * {@link RuntimeException} the executor throws is caught, logged at ERROR naming the round
     * key, and shaped into {@link Outcome.CannotExecute} (FR10, NFR-O1). A successful return
     * emits {@link EngineEvent.ExecutionFinished} before the switch, so only executed rounds
     * signal "execution done" (FR12).
     *
     * <p>Implements FR4, FR6, FR10, FR12, FR13, NFR-O1 of add-stage-engine.
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
        var feedback = priorFailures(state);
        ExecutionResult result;
        try {
            result = executor.execute(new StageExecutor.Request(context, stage, workspace, number, feedback));
        } catch (RuntimeException ex) {
            log.error("executor threw for {}", key, ex);
            return new RoundOutcome.CannotExecute(key, StackTraces.render(ex));
        }
        Events.emit(listener, new EngineEvent.ExecutionFinished(key, result.usage()));
        return switch (result) {
            case ExecutionResult.Completed completed ->
                verified(context, workspace, stage, key, number, startedAt, completed);
            case ExecutionResult.DecisionNeeded decision -> needsDecision(key, number, startedAt, decision);
        };
    }

    /**
     * Runs the verify chain for a {@link ExecutionResult.Completed} round, derives its overall
     * {@link Verdict} from the chain (design D4), and captures it as an {@link AttemptRecord} with
     * the executor + judge usage and an explicit {@link AttemptRecord.Result} classification of that
     * verdict (FR13, design D5). The verdict is computed once and reused for both the record's
     * result and the returned {@link Outcome.Verified}.
     */
    private RoundOutcome.Verified verified(
            TaskContext context,
            Workspace workspace,
            StageDefinition stage,
            AttemptKey key,
            int number,
            Instant startedAt,
            ExecutionResult.Completed completed) {
        var verification = verifyOrchestrator.verify(stage.verify(), context, workspace, key);
        var verdict = overallVerdict(verification);
        var record = new AttemptRecord(
                number,
                resultOf(verdict),
                startedAt,
                verification.results(),
                completed.usage(),
                verification.judgeUsage());
        return new RoundOutcome.Verified(key, record, verdict, completed.trace());
    }

    /**
     * Records a {@link ExecutionResult.DecisionNeeded} round WITHOUT verifying (FR6): its executor
     * usage, no check results, and an explicit {@link AttemptRecord.Result#DECISION_NEEDED}
     * classification (FR13, design D5), the {@code question}/{@code options} carried verbatim.
     */
    private static RoundOutcome.NeedsDecision needsDecision(
            AttemptKey key, int number, Instant startedAt, ExecutionResult.DecisionNeeded decision) {
        var record = new AttemptRecord(
                number,
                AttemptRecord.Result.DECISION_NEEDED,
                startedAt,
                List.of(),
                decision.usage(),
                JudgeUsage.none());
        return new RoundOutcome.NeedsDecision(key, record, decision.trace(), decision.question(), decision.options());
    }

    /**
     * Maps a round's overall {@link Verdict} to its explicit {@link AttemptRecord.Result}
     * classification (FR13, design D5): {@link Verdict.Pass} → {@link AttemptRecord.Result#PASSED},
     * {@link Verdict.Fail} → {@link AttemptRecord.Result#QUALITY_FAILURE}, {@link
     * Verdict.CannotVerify} → {@link AttemptRecord.Result#CANNOT_VERIFY}. Exhaustive switch, no
     * {@code default}, so a new verdict variant is a compile error here.
     */
    private static AttemptRecord.Result resultOf(Verdict verdict) {
        return switch (verdict) {
            case Verdict.Pass ignored -> AttemptRecord.Result.PASSED;
            case Verdict.Fail ignored -> AttemptRecord.Result.QUALITY_FAILURE;
            case Verdict.CannotVerify ignored -> AttemptRecord.Result.CANNOT_VERIFY;
        };
    }

    /**
     * Collects every non-{@link Verdict.Pass} result of all prior recorded rounds, in round
     * order, into the feedback the next executor request carries (FR4). Empty on the first round.
     */
    private static List<CheckResult> priorFailures(TaskState state) {
        var failures = new ArrayList<CheckResult>();
        for (var attempt : state.attempts()) {
            for (var result : attempt.checkResults()) {
                if (!(result.verdict() instanceof Verdict.Pass)) {
                    failures.add(result);
                }
            }
        }
        return failures;
    }

    /**
     * Derives the round's overall verdict from the verify chain (design D4): an empty chain passes
     * vacuously; otherwise the chain's last result — the pass, or the {@link Verdict.Fail}/{@link
     * Verdict.CannotVerify} that broke it.
     */
    private static Verdict overallVerdict(VerificationResult verification) {
        var results = verification.results();
        if (results.isEmpty()) {
            return new Verdict.Pass();
        }
        return results.getLast().verdict();
    }
}
