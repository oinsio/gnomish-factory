package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.console.DialogConsole;
import com.github.oinsio.gnomish.adapter.console.InteractiveExternalCheckClient;
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper;
import com.github.oinsio.gnomish.domain.engine.Engine;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
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
 * initial {@link TaskState} are known: the shared {@link DialogConsole}, the manifest-driven or
 * interactive executor/judge adapters, and the assembled {@link EnginePorts} (design D10).
 * Extracted from {@link ManualRunRunner} to keep both files within the file-size guidance.
 *
 * <p>Exactly one {@link StatusSnapshotHolder} and exactly one {@link DialogConsole} are built per
 * {@link #assemble} call (design D1): the holder feeds both the {@link StatusEventListener}
 * (engine-event side) and the {@link SnapshotActivityTracker} (prompt side, via the console); the
 * console then backs every interactive port and the outcome loop's own dialogs.
 *
 * <p>The default {@link StageExecutor}/{@link JudgeVoter} pair is the real CLI adapter for each
 * role — every stage reaching the engine is {@code agent-cli} by construction; {@code
 * --interactive} (design D6) swaps one or both roles back to the add-manual-run console adapters
 * via {@link RunArguments.InteractiveMode}. Selection itself (and each adapter's live-progress
 * wiring, FR7, NFR-O1, UX1) is delegated to {@link ExecutorAdapterSelector}, kept out of this
 * class purely to stay within the file-size guidance. The external-check client stays the
 * interactive console adapter unconditionally — no CLI-based external-check client exists (NG4 of
 * add-agent-executor).
 *
 * <p>Implements FR7, FR10, NFR-O1, UX1, D6, D10 of add-agent-executor; D10 of add-manual-run; FR7
 * of add-git-workflow.
 */
final class ManualRunAssembly {

    private final SystemConsoleIO systemConsoleIO;
    private final FilesExistCheckRunner filesExistCheckRunner;
    private final ShellCommandCheckRunner shellCommandCheckRunner;
    private final SystemClock systemClock;
    private final ThreadSleeper threadSleeper;
    private final FactoryProperties factoryProperties;

    ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties) {
        this.systemConsoleIO = systemConsoleIO;
        this.filesExistCheckRunner = filesExistCheckRunner;
        this.shellCommandCheckRunner = shellCommandCheckRunner;
        this.systemClock = systemClock;
        this.threadSleeper = threadSleeper;
        this.factoryProperties = factoryProperties;
    }

    /**
     * Builds the per-run {@link RunnerOutcomeLoop} and {@link EnginePorts} for one {@code gnomish
     * run} invocation. {@code attemptPersistence} is supplied by the caller, not fixed at
     * construction (design D8 of add-git-workflow): in-place mode passes the shared in-memory
     * bean, git mode a fresh git-backed persistence rooted at the task worktree.
     *
     * <p>Implements FR7, FR10, NFR-O1, UX1, D6, D10 of add-agent-executor; D10 of add-manual-run; FR7
     * of add-git-workflow.
     *
     * @param definition the loaded pipeline the run advances through; never null
     * @param context the synthesized task's identity; never null
     * @param initialState the synthesized task's initial state; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter instead
     *     of the manifest-driven CLI adapter (FR10, design D6); never null
     * @param attemptPersistence the {@code AttemptPersistence} realization this run commits
     *     rounds through; never null
     * @return the outcome loop and the ports it drives; never null
     */
    Run assemble(
            PipelineDefinition definition,
            TaskContext context,
            TaskState initialState,
            RunArguments.InteractiveMode interactiveMode,
            AttemptPersistence attemptPersistence) {
        var holder = new StatusSnapshotHolder(initialState, resolveAttemptLimit(definition, initialState.position()));
        var statusRenderer = new ConsoleStatusRenderer(holder, context, new StatusTextRenderer());
        var activityTracker = new SnapshotActivityTracker(holder, systemClock);
        var console = new DialogConsole(systemConsoleIO, statusRenderer, activityTracker);

        var listener = new CompositeEngineEventListener(List.of(
                new StatusEventListener(holder, systemClock), new MdcEventListener(), new LoggingEventListener()));
        var ports = new EnginePorts(
                ExecutorAdapterSelector.stageExecutor(console, interactiveMode, holder, factoryProperties, systemClock),
                filesExistCheckRunner,
                shellCommandCheckRunner,
                new InteractiveExternalCheckClient(console),
                ExecutorAdapterSelector.judgeVoter(console, interactiveMode, factoryProperties, systemClock),
                listener,
                attemptPersistence,
                systemClock,
                threadSleeper);

        var loop = new RunnerOutcomeLoop(new Engine(), console, java.time.Clock.systemUTC());
        return new Run(loop, ports, holder);
    }

    /**
     * Builds a standalone {@link DialogConsole} for a resume dialog that runs before any {@link
     * #assemble} call (design D9, task 4.7 of add-git-workflow), delegated to {@link
     * ResumeDialogConsoleFactory} — kept out of this class purely to stay within the file-size
     * guidance; see that class for the full rationale.
     *
     * @param context the resumed task's identity and decisions, for the console's {@code status}
     *     meta-command; never null
     * @param state the resumed task's current state, seeding the status snapshot the console's
     *     {@code status}/{@code status --json} meta-commands render from; never null
     * @return a fresh {@link DialogConsole} wired the same way {@link #assemble} wires its own
     */
    DialogConsole dialogConsole(TaskContext context, TaskState state) {
        return ResumeDialogConsoleFactory.build(systemConsoleIO, systemClock, context, state);
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

    /**
     * The collaborators {@link ManualRunRunner} needs once {@link #assemble} has built them.
     * {@code holder} is exposed so a caller (or a test, task 9.4) can observe the same {@link
     * StatusSnapshotHolder} the wired executor's progress listener enriches.
     */
    record Run(RunnerOutcomeLoop loop, EnginePorts ports, StatusSnapshotHolder holder) {}
}
