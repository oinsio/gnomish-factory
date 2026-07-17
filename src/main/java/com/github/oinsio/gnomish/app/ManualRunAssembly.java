package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.console.DialogConsole;
import com.github.oinsio.gnomish.adapter.console.InteractiveExternalCheckClient;
import com.github.oinsio.gnomish.adapter.console.InteractiveJudgeVoter;
import com.github.oinsio.gnomish.adapter.console.InteractiveStageExecutor;
import com.github.oinsio.gnomish.adapter.console.StageBriefing;
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO;
import com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper;
import com.github.oinsio.gnomish.domain.engine.Engine;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.status.CompositeEngineEventListener;
import com.github.oinsio.gnomish.status.ConsoleStatusRenderer;
import com.github.oinsio.gnomish.status.LoggingEventListener;
import com.github.oinsio.gnomish.status.MdcEventListener;
import com.github.oinsio.gnomish.status.SnapshotActivityTracker;
import com.github.oinsio.gnomish.status.StatusEventListener;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.util.List;

/**
 * Builds the per-run collaborators {@link ManualRunRunner} needs once a {@link TaskContext} and
 * initial {@link TaskState} are known: the shared {@link DialogConsole}, the interactive adapters
 * built on it, and the assembled {@link EnginePorts} (design D10). Extracted from {@link
 * ManualRunRunner} purely to keep both files within the project's file-size guidance
 * (`.claude/rules/process-invariants.md`); the behavior is unchanged.
 *
 * <p>Exactly one {@link StatusSnapshotHolder} and exactly one {@link DialogConsole} are built per
 * {@link #assemble} call, honoring the single-input-choke-point invariant (design D1): the holder
 * feeds both the {@link StatusEventListener} (engine-event side) and the {@link
 * SnapshotActivityTracker} (prompt side, via the console); the console then backs every
 * interactive port and the outcome loop's own dialogs.
 *
 * <p>Implements D10 of add-manual-run.
 */
final class ManualRunAssembly {

    private final SystemConsoleIO systemConsoleIO;
    private final FilesExistCheckRunner filesExistCheckRunner;
    private final ShellCommandCheckRunner shellCommandCheckRunner;
    private final InMemoryAttemptPersistence attemptPersistence;
    private final SystemClock systemClock;
    private final ThreadSleeper threadSleeper;

    ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            InMemoryAttemptPersistence attemptPersistence,
            SystemClock systemClock,
            ThreadSleeper threadSleeper) {
        this.systemConsoleIO = systemConsoleIO;
        this.filesExistCheckRunner = filesExistCheckRunner;
        this.shellCommandCheckRunner = shellCommandCheckRunner;
        this.attemptPersistence = attemptPersistence;
        this.systemClock = systemClock;
        this.threadSleeper = threadSleeper;
    }

    /**
     * Builds the per-run {@link RunnerOutcomeLoop} and {@link EnginePorts} for one {@code gnomish
     * run} invocation.
     *
     * <p>Implements D10 of add-manual-run.
     *
     * @param definition the loaded pipeline the run advances through; never null
     * @param context the synthesized task's identity; never null
     * @param initialState the synthesized task's initial state; never null
     * @return the outcome loop and the ports it drives; never null
     */
    Run assemble(PipelineDefinition definition, TaskContext context, TaskState initialState) {
        var holder = new StatusSnapshotHolder(initialState, resolveAttemptLimit(definition, initialState.position()));
        var statusRenderer = new ConsoleStatusRenderer(holder, context, new StatusTextRenderer());
        var activityTracker = new SnapshotActivityTracker(holder, systemClock);
        var console = new DialogConsole(systemConsoleIO, statusRenderer, activityTracker);

        var listener = new CompositeEngineEventListener(List.of(
                new StatusEventListener(holder, systemClock), new MdcEventListener(), new LoggingEventListener()));
        var ports = new EnginePorts(
                new InteractiveStageExecutor(console, new StageBriefing()),
                filesExistCheckRunner,
                shellCommandCheckRunner,
                new InteractiveExternalCheckClient(console),
                new InteractiveJudgeVoter(console),
                listener,
                attemptPersistence,
                systemClock,
                threadSleeper);

        var loop = new RunnerOutcomeLoop(new Engine(), console, java.time.Clock.systemUTC());
        return new Run(loop, ports);
    }

    /**
     * Resolves the starting stage's attempt limit for the {@link StatusSnapshotHolder}'s initial
     * value: {@code StageDefinition.limits()} for the position's named stage when found, else the
     * pipeline's default limits — a fallback that only matters if {@code position} ever named a
     * stage absent from {@code definition} (the engine's own {@code PipelineMismatch} check, not
     * re-implemented here).
     */
    private static int resolveAttemptLimit(PipelineDefinition definition, Position position) {
        if (!(position instanceof Position.AtStage atStage)) {
            return definition.defaultLimits().attemptLimit();
        }
        for (StageDefinition stage : definition.stages()) {
            if (stage.name().equals(atStage.name())) {
                return stage.limits().attemptLimit();
            }
        }
        return definition.defaultLimits().attemptLimit();
    }

    /** The two collaborators {@link ManualRunRunner} needs once {@link #assemble} has built them. */
    record Run(RunnerOutcomeLoop loop, EnginePorts ports) {}
}
