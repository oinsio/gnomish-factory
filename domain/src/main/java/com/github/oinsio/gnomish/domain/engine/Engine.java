package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The pure orchestrator that drives one task from its recorded {@link TaskState} to a
 * terminal {@link TaskOutcome} (design D1): {@link #run} takes the pipeline, the task
 * context, the recorded state, the opaque workspace and the {@link EnginePorts} bundle,
 * and returns an outcome the caller acts on — the engine itself touches no tracker,
 * filesystem or git, and escalation is a returned value, never a control-flow signal.
 *
 * <p>Reentrant and free of shared mutable state: an {@code Engine} holds nothing at all,
 * so one instance drives concurrent runs with independent ports safely (NFR-R1). Every
 * run is framed by a {@link EngineEvent.RunStarted} and a {@link EngineEvent.TaskFinished}
 * emitted through the shared swallow-and-log {@link Events#emit} helper, so an event-driven
 * wrapper always sees the run begin and end (design D7).
 *
 * <p>Implements FR1, FR5, FR8, FR9, FR12, FR14, NFR-S1 of add-stage-engine — the engine
 * executes nothing itself: commands, processes and model calls happen only behind ports.
 */
public final class Engine {

    /**
     * Runs the task described by {@code context} from its recorded {@code state} through
     * the pipeline {@code definition}, returning the terminal {@link TaskOutcome}. Emits
     * {@link EngineEvent.RunStarted} first, then resolves the pre-flight terminals that
     * reach no execution or persistence port: a {@link Position.PipelineEnd} completes
     * immediately (FR8); an {@link Position.AtStage} or {@link Position.AwaitingApproval}
     * name absent from the pipeline escalates as {@link EscalationReport.PipelineMismatch}
     * (FR9); a {@link Position.AwaitingApproval} gate pauses again as {@link
     * TaskOutcome.Paused} (FR2 of make-checkpoint-gate-durable); a last recorded round carrying
     * a {@link Stop} re-escalates from the record (FR6 of make-checkpoint-gate-durable); an
     * {@code attemptsUsed} at the stage's attempt limit escalates as {@link
     * EscalationReport.AttemptsExhausted} (FR5). Otherwise the stage attempt loop runs. Every path emits {@link EngineEvent.TaskFinished} with the outcome (FR12).
     *
     * <p>Holds no state across the call, so concurrent runs stay isolated (NFR-R1).
     *
     * <p>Implements FR1, FR5, FR8, FR9, FR12 of add-stage-engine; FR6 of make-checkpoint-gate-durable.
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
        // A state read back from the branch may carry the attempts of a stage that already passed:
        // its pass and the advance it implied landed in one commit (FR4 of
        // harden-task-branch-contract), so the position names the next stage while the list still
        // describes the finished one. Normalizing here keeps the resumed stage's first round at
        // round zero and its attempt history its own. A no-op within a live run, where the engine's
        // own advance already emptied the list.
        var resumed = state.startOfStage();
        Events.emit(listener, new EngineEvent.RunStarted(context.taskId(), resumed.position(), resumed.attemptsUsed()));
        var outcome = preflight(definition, context, resumed, workspace, ports);
        Events.emit(listener, new EngineEvent.TaskFinished(context.taskId(), outcome));
        return outcome;
    }

    /**
     * Resolves the run against its {@link Position} with an exhaustive switch — no
     * {@code default}, so a new variant fails to compile. {@link Position.PipelineEnd}
     * completes immediately (FR8); {@link Position.AwaitingApproval} escalates as {@link
     * EscalationReport.PipelineMismatch} when the pipeline no longer declares the gate's stage
     * (FR9) and otherwise pauses again at the gate (FR2 of make-checkpoint-gate-durable);
     * {@link Position.AtStage} is resolved by {@link #atStage}, the only arm that threads the
     * {@code context}, {@code workspace} and {@code ports} on to the stage attempt loop.
     */
    private TaskOutcome preflight(
            PipelineDefinition definition,
            TaskContext context,
            TaskState state,
            Workspace workspace,
            EnginePorts ports) {
        return switch (state.position()) {
            case Position.PipelineEnd ignored -> new TaskOutcome.Completed(state);
            case Position.AtStage atStage -> atStage(definition, context, state, workspace, ports, atStage.name());
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
     * Resolves an {@link Position.AtStage} run: a stage name absent from the pipeline is a
     * {@link EscalationReport.PipelineMismatch} (FR9), a recorded stop re-escalates through the
     * live loop's own {@link StopEscalation} mapping (FR6 of make-checkpoint-gate-durable), an
     * {@code attemptsUsed} at the attempt limit is {@link EscalationReport.AttemptsExhausted}
     * (FR5) — all before any port — otherwise the stage runs through {@link #runStages}.
     */
    private TaskOutcome atStage(
            PipelineDefinition definition,
            TaskContext context,
            TaskState state,
            Workspace workspace,
            EnginePorts ports,
            String stageName) {
        var stage = definition.findStage(stageName);
        if (stage == null) {
            return mismatch(state, stageName);
        }
        // FR6 of make-checkpoint-gate-durable: a stop whose park was lost re-raises from the record.
        var recorded = StopEscalation.recorded(state);
        if (recorded.isPresent()) {
            return new TaskOutcome.Escalated(state, recorded.get());
        }
        int limit = stage.limits().attemptLimit();
        if (state.attemptsUsed() >= limit) {
            return new TaskOutcome.Escalated(state, new EscalationReport.AttemptsExhausted(limit));
        }
        return runStages(definition, context, state, workspace, ports, stage);
    }

    /**
     * The {@link EscalationReport.PipelineMismatch} for a recorded position naming
     * {@code stageName}, a stage the pipeline no longer declares (FR9). The branch-document mint
     * (design D3): the name came off a recorded position some instance wrote, and no stage in the
     * current pipeline vouches for it — which is exactly what this report says.
     */
    private static TaskOutcome mismatch(TaskState state, String stageName) {
        return new TaskOutcome.Escalated(
                state, new EscalationReport.PipelineMismatch(UntrustedText.branchDocument(stageName)));
    }

    /**
     * Drives the run stage to stage from the resolved {@code stage}, delegating each stage to a
     * reused {@link StageAttemptLoop} and applying advancement between stages (FR8). The loop and
     * its {@link VerifyOrchestrator} are built once per run from the ports, so the engine holds no
     * shared mutable state and concurrent runs stay isolated (NFR-R1).
     *
     * <p>Each iteration runs the current stage: a {@link StageResult.Terminal} ends the run with
     * its outcome; a {@link StageResult.Passed} applies the stage's {@link AdvancementMode}
     * exhaustively (no {@code default}). {@code AUTO} advances to the next stage — resetting the
     * attempt history (FR14) — or, past the last stage, completes at {@link Position.PipelineEnd}
     * (design D4); the advance below only resets the in-memory attempt history for the stage about
     * to run, its position already recorded by the round commit (FR4 of harden-task-branch-contract).
     * {@code MANUAL} pauses, naming the stage that just passed (FR8), with exactly the state the
     * round persisted — the position at the gate, {@link Position.AwaitingApproval} — and no
     * further persist or advance (FR1 of make-checkpoint-gate-durable, design D1).
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
        var verifyOrchestrator = new VerifyOrchestrator(
                ports.builtinRunner(),
                ports.commandRunner(),
                new ExternalPolling(ports.externalClient(), ports.attemptDelivery(), ports.clock(), ports.sleeper()),
                new JudgeVoting(ports.judgeVoter()),
                ports.clock(),
                ports.listener());
        var loop = new StageAttemptLoop(ports, verifyOrchestrator, definition);
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
                    currentState = passed.state().advanceTo(new Position.AtStage(next.name()));
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
