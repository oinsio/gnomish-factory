package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The pure orchestrator that drives one task from its recorded {@link TaskState} to a terminal
 * {@link TaskOutcome} (design D1): {@link #run} takes the pipeline, the task context, the recorded
 * state, the opaque workspace and the {@link EnginePorts} bundle, and returns an outcome the caller
 * acts on — the engine touches no tracker, filesystem or git, and escalation is a returned value.
 *
 * <p>Reentrant and free of shared mutable state: an {@code Engine} holds nothing, so one instance
 * drives concurrent runs with independent ports safely (NFR-R1). Every run is framed by a {@link
 * EngineEvent.RunStarted} and a {@link EngineEvent.TaskFinished} emitted through the shared
 * swallow-and-log {@link Events#emit} helper, so a wrapper always sees the run begin and end (D7).
 *
 * <p>Implements FR1, FR5, FR8, FR9, FR12, FR14, NFR-S1 of add-stage-engine (work only behind ports).
 */
public final class Engine {

    /**
     * Runs the task from its recorded {@code state} through {@code definition} to a terminal {@link
     * TaskOutcome}, between {@link EngineEvent.RunStarted} and {@link EngineEvent.TaskFinished} on every
     * path (FR12): the pre-flight terminals that reach no port ({@link #preflight}, {@link #atStage})
     * first, otherwise the stage attempt loop. Implements FR1, FR5, FR8, FR9, FR12 of add-stage-engine;
     * FR2, FR6 of make-checkpoint-gate-durable; FR13 of define-executor-contract; FR20 of
     * migrate-verify-to-executor-contract.
     *
     * @param definition the pipeline whose stages the run advances through; never null
     * @param context the task's identity and human decisions; never null
     * @param state the recorded state the run resumes from; never null
     * @param workspace the opaque working copy the run operates on; never null
     * @param ports the collaborators the engine drives; never null
     * @return the terminal outcome of the run; never null
     */
    public TaskOutcome run(
            PipelineDefinition definition,
            TaskContext context,
            TaskState state,
            Workspace workspace,
            EnginePorts ports) {
        var listener = ports.listener();
        var resumed = resumedFrom(state);
        Events.emit(listener, new EngineEvent.RunStarted(context.taskId(), resumed.position(), resumed.attemptsUsed()));
        var outcome = preflight(definition, context, state, workspace, ports);
        Events.emit(listener, new EngineEvent.TaskFinished(context.taskId(), outcome));
        return outcome;
    }

    /**
     * The state a round starts its stage's history from: {@link TaskState#startOfStage} of the last
     * persisted state (FR4 of harden-task-branch-contract), with the open round's pending entries read
     * before it empties them — a pending commit of a stage's first round carries the previous stage's
     * pass (D21 item 12, D22 of migrate-verify). The persisted state itself is what a round's pending
     * write carries ({@link StageAttemptLoop}); this view gives the round its number and feedback.
     */
    static TaskState resumedFrom(TaskState recorded) {
        TaskState normalized = recorded.startOfStage();
        var entries = recorded.pendingPolls();
        return normalized.pendingPolls().equals(entries) ? normalized : normalized.withPendingPolls(entries);
    }

    /**
     * Resolves the run against its {@link Position} with an exhaustive switch (no {@code default}):
     * {@link Position.PipelineEnd} completes (FR8); {@link Position.AwaitingApproval} is a {@link
     * EscalationReport.PipelineMismatch} for a stage the pipeline no longer declares (FR9), else it
     * pauses at the gate again (FR2 of make-checkpoint-gate-durable); {@link Position.AtStage} goes to
     * {@link #atStage}. Every outcome carries the {@link #resumedFrom} view of {@code recorded}.
     */
    private TaskOutcome preflight(
            PipelineDefinition definition,
            TaskContext context,
            TaskState recorded,
            Workspace workspace,
            EnginePorts ports) {
        var state = resumedFrom(recorded);
        return switch (state.position()) {
            case Position.PipelineEnd ignored -> new TaskOutcome.Completed(state);
            case Position.AtStage atStage -> atStage(definition, context, recorded, workspace, ports, atStage.name());
            // FR2 of make-checkpoint-gate-durable: the position is the gate — the run pauses
            // again, invoking no port, until the approval write moves the position past it. A
            // stale gate name is a PipelineMismatch first (FR9), as for AtStage.
            case Position.AwaitingApproval gate ->
                definition.findStage(gate.stage()) == null
                        ? mismatch(state, gate.stage())
                        : new TaskOutcome.Paused(state, gate.stage());
        };
    }

    /**
     * Resolves an {@link Position.AtStage} run before any port, on the {@link #resumedFrom} view: a
     * stage absent from the pipeline is a {@link EscalationReport.PipelineMismatch} (FR9), a recorded
     * stop re-escalates through {@link StopEscalation} (FR6 of make-checkpoint-gate-durable), an {@code
     * attemptsUsed} at the limit is {@link EscalationReport.AttemptsExhausted} (FR5), an executor
     * version differing from the task's pin is a {@code PipelineMismatch} naming both (FR13 of
     * define-executor-contract); otherwise {@link #runStages} runs from {@code recorded} itself.
     */
    private TaskOutcome atStage(
            PipelineDefinition definition,
            TaskContext context,
            TaskState recorded,
            Workspace workspace,
            EnginePorts ports,
            String stageName) {
        var state = resumedFrom(recorded);
        var stage = definition.findStage(stageName);
        if (stage == null) {
            return mismatch(state, stageName);
        }
        // FR6 of make-checkpoint-gate-durable: a stop whose park was lost re-raises from the record.
        var stop = StopEscalation.recorded(state);
        if (stop.isPresent()) {
            return new TaskOutcome.Escalated(state, stop.get());
        }
        int limit = stage.limits().attemptLimit();
        if (state.attemptsUsed() >= limit) {
            return new TaskOutcome.Escalated(state, new EscalationReport.AttemptsExhausted(limit));
        }
        // FR13 of define-executor-contract: an executor whose describe changed since the pin escalates first.
        var changed = ExecutorVersionCheck.compare(
                state.executorVersions(), ports.describedExecutors().all());
        if (changed.isPresent()) {
            return new TaskOutcome.Escalated(state, changed.get());
        }
        return runStages(definition, context, recorded, workspace, ports, stage);
    }

    /**
     * The {@link EscalationReport.PipelineMismatch} for a recorded position naming {@code stageName},
     * a stage the pipeline no longer declares (FR9). The branch-document mint (design D3): the name
     * came off a recorded position some instance wrote, and no stage of the pipeline vouches for it.
     */
    private static TaskOutcome mismatch(TaskState state, String stageName) {
        return new TaskOutcome.Escalated(
                state,
                new EscalationReport.PipelineMismatch(
                        new MismatchCause.StaleStage(UntrustedText.branchDocument(stageName))));
    }

    /**
     * Drives the run stage to stage from the resolved {@code stage}, delegating each stage to a
     * reused {@link StageAttemptLoop} and applying advancement between stages (FR8). The loop and
     * its {@link VerifyOrchestrator} are built once per run from the ports, so the engine holds no
     * shared mutable state and concurrent runs stay isolated (NFR-R1).
     *
     * <p>Each iteration runs the current stage: a {@link StageResult.Terminal} ends the run; a {@link
     * StageResult.Passed} applies the stage's {@link AdvancementMode} exhaustively. {@code AUTO}
     * advances — the next stage starts from the passing round's persisted state, its position already
     * advanced by the round commit (FR4 of harden-task-branch-contract), and resets the history itself
     * (FR14; {@link #resumedFrom}; D21 item 12 of migrate-verify) — or completes at {@link
     * Position.PipelineEnd} past the last stage (design D4). {@code MANUAL} pauses with exactly the
     * state the round persisted, the position at the gate (FR1 of make-checkpoint-gate-durable, D1).
     *
     * <p>Implements FR8, FR14 of add-stage-engine; FR1 of make-checkpoint-gate-durable.
     */
    private static TaskOutcome runStages(
            PipelineDefinition definition,
            TaskContext context,
            TaskState state,
            Workspace workspace,
            EnginePorts ports,
            StageDefinition stage) {
        // One owner of pending work for the run's checks and its transform rounds alike (design D11
        // of extract-agent-round-runner).
        var poll = new PendingPoll(
                ports.executor(), ports.persistence(), ports.attemptDelivery(), ports.time(), ports.listener());
        var verifyOrchestrator = new VerifyOrchestrator(
                poll, ports.describedExecutors(), ports.time().clock(), ports.listener());
        var loop = new StageAttemptLoop(ports, verifyOrchestrator, poll, definition);
        var currentState = state;
        var currentStage = stage;
        while (true) {
            var result = loop.run(context, currentState, workspace, currentStage);
            if (result instanceof StageResult.Terminal(var outcome)) {
                return outcome;
            }
            var passed = (StageResult.Passed) result;
            var next = Advancement.nextStage(definition, currentStage);
            switch (currentStage.advancement()) {
                case AUTO -> {
                    if (next == null) {
                        return new TaskOutcome.Completed(passed.state().advanceTo(new Position.PipelineEnd()));
                    }
                    currentState = passed.state();
                    currentStage = next;
                }
                case MANUAL -> {
                    // FR1 of make-checkpoint-gate-durable: the round commit already recorded the
                    // gate, AwaitingApproval(stage); the outcome carries exactly that persisted
                    // state — no in-memory advance past a gate no approval has opened.
                    return new TaskOutcome.Paused(passed.state(), currentStage.name());
                }
            }
        }
    }
}
