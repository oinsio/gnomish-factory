package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import org.jspecify.annotations.Nullable;

/**
 * The per-stage attempt loop the {@link Engine} delegates to once pre-flight clears the run
 * to execute: it drives one round at a time through {@link RoundExecution}, then routes the
 * round's sealed {@link RoundExecution.Outcome}, delegating the round's persistence and events
 * to the {@link AttemptJournal} (design D4, D7; the "Per-round port choreography" diagram).
 * Each round is emitted as {@link EngineEvent.AttemptStarted}, executed, recorded into a new
 * {@link TaskState}, persisted synchronously, then emitted as {@link EngineEvent.AttemptFinished}
 * — persist strictly before the finish event and any next attempt (FR11). One
 * {@code StageAttemptLoop} is constructed per run from the run's {@link EnginePorts}, holding
 * only immutable collaborators so the engine stays reentrant (NFR-R1).
 *
 * <p>A round's outcome routes through a single exhaustive switch (design D4). A {@link
 * RoundExecution.Outcome.Verified} routes on its verdict: a {@link Verdict.Fail} burns an
 * attempt and, when that burn reaches the stage's resolved limit, escalates as {@link
 * EscalationReport.AttemptsExhausted} with the full recorded attempt history (FR5) — otherwise
 * it retries the stage, feeding the failed check results of ALL prior attempts forward (FR4,
 * FR5); a {@link Verdict.CannotVerify} records the round unburned and escalates {@link
 * EscalationReport.CannotVerify} naming the failing check (FR4, FR13); a {@link Verdict.Pass}
 * hands the recorded, persisted state back as {@link StageResult.Passed} for the {@link Engine}
 * to advance (FR8). A {@link RoundExecution.Outcome.NeedsDecision} records the round
 * unburned and escalates {@link EscalationReport.DecisionNeeded} verbatim, without burning an
 * attempt (FR6, design D6). A {@link RoundExecution.Outcome.CannotExecute} — the executor port
 * threw — escalates {@link EscalationReport.CannotExecute} from the entry state: no round ran,
 * so nothing is recorded, persisted or emitted and no attempt is burned (FR10, NFR-O1). A
 * thrown {@code persist} instead ends the run as {@link TaskOutcome.Aborted} through the
 * journal (FR11, NFR-O1).
 *
 * <p>Implements FR4, FR5, FR6, FR8, FR10, FR11, FR13, NFR-O1 of add-stage-engine.
 */
final class StageAttemptLoop {

    private final RoundExecution roundExecution;
    private final AttemptJournal journal;

    /**
     * Wires the loop from a run's ports: the {@link RoundExecution} that runs each round is
     * built from the executor and {@code verifyOrchestrator}, and the {@link AttemptJournal}
     * that owns per-round persistence and events is built from the run's listener and
     * persistence ports. All immutable, so the loop carries no mutable state (NFR-R1).
     *
     * @param ports the run's collaborators the loop reads executor/listener/persistence from;
     *     never null
     * @param verifyOrchestrator the verify chain the round's checks run through; never null
     */
    StageAttemptLoop(EnginePorts ports, VerifyOrchestrator verifyOrchestrator) {
        this.roundExecution = new RoundExecution(ports.executor(), verifyOrchestrator, ports.listener());
        this.journal = new AttemptJournal(ports.listener(), ports.persistence());
    }

    /**
     * Drives {@code stage} from the recorded {@code state} to a {@link StageResult}, looping
     * over rounds within the stage. Each iteration emits {@link EngineEvent.AttemptStarted}
     * through the journal, executes the round through {@link RoundExecution}, then routes its
     * sealed {@link RoundExecution.Outcome} through an exhaustive switch: a {@link
     * RoundExecution.Outcome.Verified} routes on its verdict — a {@link Verdict.Fail} retries
     * or escalates {@link EscalationReport.AttemptsExhausted} at the limit (FR4, FR5), a {@link
     * Verdict.CannotVerify} escalates (FR4), a {@link Verdict.Pass} hands the recorded, persisted
     * state back as {@link StageResult.Passed} for the {@link Engine} to advance (FR8); a {@link
     * RoundExecution.Outcome.NeedsDecision} escalates {@link EscalationReport.DecisionNeeded}
     * without burning an attempt (FR6); a {@link RoundExecution.Outcome.CannotExecute} escalates
     * {@link EscalationReport.CannotExecute} from the entry state with nothing recorded, persisted
     * or emitted (FR10). Every result but {@code Passed} is a {@link StageResult.Terminal} the
     * engine returns verbatim. A completed round is recorded and committed (persist →
     * AttemptFinished) before it routes; the loop threads {@code state} through one local
     * variable, so the engine stays reentrant (NFR-R1).
     *
     * <p>Implements FR4, FR5, FR6, FR8, FR10, FR11, FR13 of add-stage-engine.
     *
     * @param context the task's identity and human decisions; never null
     * @param state the recorded state this stage resumes from; never null
     * @param workspace the opaque working copy the rounds operate on; never null
     * @param stage the resolved stage these rounds run; never null
     * @return the stage's result — {@link StageResult.Passed} to advance or a {@link
     *     StageResult.Terminal} to return; never null
     */
    StageResult run(TaskContext context, TaskState state, Workspace workspace, StageDefinition stage) {
        var current = state;
        while (true) {
            journal.started(new AttemptKey(
                    context.taskId(), stage.name(), current.attempts().size()));
            switch (roundExecution.execute(context, current, workspace, stage)) {
                case RoundExecution.Outcome.Verified verified -> {
                    var newState = newState(current, verified);
                    var aborted = journal.commit(context.taskId(), newState, verified.key(), verified.trace());
                    if (aborted != null) {
                        return new StageResult.Terminal(aborted);
                    }
                    var routed = route(newState, verified, stage);
                    if (routed != null) {
                        return routed;
                    }
                    current = newState;
                }
                case RoundExecution.Outcome.NeedsDecision decision -> {
                    var newState = current.recordUnburnedRound(decision.record());
                    var aborted = journal.commit(context.taskId(), newState, decision.key(), decision.trace());
                    if (aborted != null) {
                        return new StageResult.Terminal(aborted);
                    }
                    return new StageResult.Terminal(new TaskOutcome.Escalated(
                            newState, new EscalationReport.DecisionNeeded(decision.question(), decision.options())));
                }
                case RoundExecution.Outcome.CannotExecute ce -> {
                    return new StageResult.Terminal(
                            new TaskOutcome.Escalated(current, new EscalationReport.CannotExecute(ce.cause())));
                }
            }
        }
    }

    /**
     * Records a {@link RoundExecution.Outcome.Verified} round into a new {@link TaskState}
     * (FR13): a {@link Verdict.Fail} burns one attempt via {@link TaskState#recordQualityFailure},
     * every other verdict records the round without counting it via {@link
     * TaskState#recordUnburnedRound}. Recorded before persisting, so routing later reads an
     * already-computed state and never re-records.
     */
    private static TaskState newState(TaskState state, RoundExecution.Outcome.Verified verified) {
        if (verified.verdict() instanceof Verdict.Fail) {
            return state.recordQualityFailure(verified.record());
        }
        return state.recordUnburnedRound(verified.record());
    }

    /**
     * Routes a committed {@link RoundExecution.Outcome.Verified} round on its verdict, returning
     * the {@link StageResult} it resolves to or {@code null} to retry the stage. A {@link
     * Verdict.Fail} at the resolved limit escalates {@link EscalationReport.AttemptsExhausted} as
     * a {@link StageResult.Terminal} (FR5), otherwise returns {@code null} to retry (FR4); a
     * {@link Verdict.CannotVerify} escalates {@link EscalationReport.CannotVerify} naming the
     * failing check as a {@link StageResult.Terminal} (FR4); a {@link Verdict.Pass} hands the
     * recorded, persisted {@code newState} back as {@link StageResult.Passed} for the {@link
     * Engine} to advance (FR8).
     */
    @Nullable
    private static StageResult route(
            TaskState newState, RoundExecution.Outcome.Verified verified, StageDefinition stage) {
        return switch (verified.verdict()) {
            case Verdict.Fail ignored -> exhausted(newState, stage) ? exhaust(newState, stage) : null;
            case Verdict.CannotVerify cv -> escalate(newState, verified, cv);
            case Verdict.Pass ignored -> new StageResult.Passed(newState);
        };
    }

    /**
     * Escalates a {@link Verdict.CannotVerify} round as a {@link StageResult.Terminal} carrying
     * {@link EscalationReport.CannotVerify} naming the failing check with its reason and details
     * (FR4); the round is already recorded in {@code newState} and persisted, so this only names
     * the terminal.
     */
    private static StageResult escalate(
            TaskState newState, RoundExecution.Outcome.Verified verified, Verdict.CannotVerify cv) {
        var check = verified.record().checkResults().getLast().checkRef();
        return new StageResult.Terminal(new TaskOutcome.Escalated(
                newState, new EscalationReport.CannotVerify(check, cv.reason(), cv.details())));
    }

    /**
     * True when the just-burned quality-failure round in {@code newState} has reached the
     * stage's resolved attempt limit, so no further retry is permitted (FR5).
     */
    private static boolean exhausted(TaskState newState, StageDefinition stage) {
        return newState.attemptsUsed() >= stage.limits().attemptLimit();
    }

    /**
     * Escalates the exhausted stage as a {@link StageResult.Terminal} carrying {@link
     * EscalationReport.AttemptsExhausted} with the stage's resolved limit (FR5). The exhausting
     * round is already recorded and persisted into {@code newState}, whose {@code attempts} hold
     * every failed round of the stage — so the outcome and its final state alone render the
     * escalation (UX1).
     */
    private static StageResult exhaust(TaskState newState, StageDefinition stage) {
        return new StageResult.Terminal(new TaskOutcome.Escalated(
                newState, new EscalationReport.AttemptsExhausted(stage.limits().attemptLimit())));
    }
}
