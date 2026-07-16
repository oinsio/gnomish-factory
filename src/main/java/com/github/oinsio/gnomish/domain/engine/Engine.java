package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.port.Workspace;
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import org.jspecify.annotations.Nullable;

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
     * immediately (FR8); an {@link Position.AtStage} name absent from the pipeline
     * escalates as {@link EscalationReport.PipelineMismatch} (FR9); an {@code attemptsUsed}
     * already at the stage's resolved attempt limit escalates as
     * {@link EscalationReport.AttemptsExhausted} (FR5). Otherwise the stage attempt loop
     * runs. Every path emits {@link EngineEvent.TaskFinished} with the outcome (FR12).
     *
     * <p>Holds no state across the call, so concurrent runs stay isolated (NFR-R1).
     *
     * <p>Implements FR1, FR5, FR8, FR9, FR12 of add-stage-engine.
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
        Events.emit(listener, new EngineEvent.RunStarted(context.taskId(), state.position(), state.attemptsUsed()));
        var outcome = preflight(definition, context, state, workspace, ports);
        Events.emit(listener, new EngineEvent.TaskFinished(context.taskId(), outcome));
        return outcome;
    }

    /**
     * Resolves the run against its {@link Position} with an exhaustive switch — no
     * {@code default}, so a new variant fails to compile. {@link Position.PipelineEnd}
     * completes immediately (FR8); {@link Position.AtStage} looks the stage up by name
     * and either escalates as {@link EscalationReport.PipelineMismatch} when absent (FR9),
     * escalates as {@link EscalationReport.AttemptsExhausted} when the resolved limit is
     * already reached (FR5), or hands off to the stage attempt loop.
     *
     * <p>Threads the executor/persistence-bearing {@code context}, {@code workspace} and
     * {@code ports} through to {@link #atStage}, which hands them to the stage attempt
     * loop once the pre-flight terminals clear; the {@link Position.PipelineEnd} arm needs
     * only the {@code state} to complete immediately (FR8).
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
        };
    }

    /**
     * Resolves an {@link Position.AtStage} run: a stage name absent from the pipeline is a
     * {@link EscalationReport.PipelineMismatch} (FR9), an {@code attemptsUsed} at or above
     * the resolved attempt limit is an {@link EscalationReport.AttemptsExhausted} (FR5) —
     * both pre-flight, before any execution or persistence port is touched — otherwise the
     * resolved stage and the run's collaborators are handed to the stage attempt loop
     * through {@link #runStages}.
     */
    private TaskOutcome atStage(
            PipelineDefinition definition,
            TaskContext context,
            TaskState state,
            Workspace workspace,
            EnginePorts ports,
            String stageName) {
        var stage = findStage(definition, stageName);
        if (stage == null) {
            return new TaskOutcome.Escalated(state, new EscalationReport.PipelineMismatch(stageName));
        }
        int limit = stage.limits().attemptLimit();
        if (state.attemptsUsed() >= limit) {
            return new TaskOutcome.Escalated(state, new EscalationReport.AttemptsExhausted(limit));
        }
        return runStages(definition, context, state, workspace, ports, stage);
    }

    /**
     * Looks the stage named {@code stageName} up in the pipeline's declared order,
     * returning it or {@code null} when the pipeline no longer declares it — the signal
     * {@link #atStage} turns into a {@link EscalationReport.PipelineMismatch} (FR9).
     */
    @Nullable
    private static StageDefinition findStage(PipelineDefinition definition, String stageName) {
        for (var stage : definition.stages()) {
            if (stage.name().equals(stageName)) {
                return stage;
            }
        }
        return null;
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
     * (design D4). {@code MANUAL} pauses with the position already advanced past the paused stage,
     * naming the stage that just passed (FR8). The advanced state is the returned outcome — the
     * passing round was already persisted by the loop, so no extra persist happens here.
     *
     * <p>Implements FR8, FR14 of add-stage-engine.
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
                new ExternalPolling(ports.externalClient(), ports.clock(), ports.sleeper()),
                new JudgeVoting(ports.judgeVoter()),
                ports.clock(),
                ports.listener());
        var loop = new StageAttemptLoop(ports, verifyOrchestrator);
        var currentState = state;
        var currentStage = stage;
        while (true) {
            var result = loop.run(context, currentState, workspace, currentStage);
            if (result instanceof StageResult.Terminal terminal) {
                return terminal.outcome();
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
                    return new TaskOutcome.Paused(
                            passed.state().advanceTo(Advancement.nextPosition(next)), currentStage.name());
                }
            }
        }
    }
}
