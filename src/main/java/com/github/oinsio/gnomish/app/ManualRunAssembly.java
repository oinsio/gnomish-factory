package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.agent.AgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.CliJudgeVoter;
import com.github.oinsio.gnomish.adapter.agent.CliStageExecutor;
import com.github.oinsio.gnomish.adapter.agent.CompositeAgentProgressListener;
import com.github.oinsio.gnomish.adapter.agent.LoggingAgentProgressListener;
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
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.status.AgentActivityEnricher;
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
 * Extracted from {@link ManualRunRunner} purely to keep both files within the project's
 * file-size guidance (`.claude/rules/process-invariants.md`).
 *
 * <p>Exactly one {@link StatusSnapshotHolder} and exactly one {@link DialogConsole} are built per
 * {@link #assemble} call, honoring the single-input-choke-point invariant (design D1): the holder
 * feeds both the {@link StatusEventListener} (engine-event side) and the {@link
 * SnapshotActivityTracker} (prompt side, via the console); the console then backs every
 * interactive port and the outcome loop's own dialogs.
 *
 * <p>The default {@link StageExecutor}/{@link JudgeVoter} pair is the real CLI adapter for each
 * role — every stage reaching the engine is {@code agent-cli} by construction (task 9.2's
 * startup fail-fast rejects {@code api} stages before any dialog); {@code --interactive} (design
 * D6) swaps one or both roles back to the add-manual-run console adapters via {@link
 * RunArguments.InteractiveMode}. The external-check client stays the interactive console adapter
 * unconditionally — no CLI-based external-check client exists in this change (NG4 of
 * add-agent-executor).
 *
 * <p>Each CLI adapter's rounds feed live progress (task 9.4, D10) to a {@link
 * LoggingAgentProgressListener} (FR7, NFR-O1, UX1); the executor also gets an {@link
 * AgentActivityEnricher} bound to the same {@link StatusSnapshotHolder}, fanned out via {@link
 * CompositeAgentProgressListener} — the judge gets the renderer alone, literally "executor rounds
 * only" per D10.
 *
 * <p>Implements FR7, FR10, NFR-O1, UX1, D6, D10 of add-agent-executor; D10 of add-manual-run.
 */
final class ManualRunAssembly {

    private final SystemConsoleIO systemConsoleIO;
    private final FilesExistCheckRunner filesExistCheckRunner;
    private final ShellCommandCheckRunner shellCommandCheckRunner;
    private final InMemoryAttemptPersistence attemptPersistence;
    private final SystemClock systemClock;
    private final ThreadSleeper threadSleeper;
    private final FactoryProperties factoryProperties;

    ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            InMemoryAttemptPersistence attemptPersistence,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties) {
        this.systemConsoleIO = systemConsoleIO;
        this.filesExistCheckRunner = filesExistCheckRunner;
        this.shellCommandCheckRunner = shellCommandCheckRunner;
        this.attemptPersistence = attemptPersistence;
        this.systemClock = systemClock;
        this.threadSleeper = threadSleeper;
        this.factoryProperties = factoryProperties;
    }

    /**
     * Builds the per-run {@link RunnerOutcomeLoop} and {@link EnginePorts} for one {@code gnomish
     * run} invocation.
     *
     * <p>Implements FR7, FR10, NFR-O1, UX1, D6, D10 of add-agent-executor; D10 of add-manual-run.
     *
     * @param definition the loaded pipeline the run advances through; never null
     * @param context the synthesized task's identity; never null
     * @param initialState the synthesized task's initial state; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter instead
     *     of the manifest-driven CLI adapter (FR10, design D6); never null
     * @return the outcome loop and the ports it drives; never null
     */
    Run assemble(
            PipelineDefinition definition,
            TaskContext context,
            TaskState initialState,
            RunArguments.InteractiveMode interactiveMode) {
        var holder = new StatusSnapshotHolder(initialState, resolveAttemptLimit(definition, initialState.position()));
        var statusRenderer = new ConsoleStatusRenderer(holder, context, new StatusTextRenderer());
        var activityTracker = new SnapshotActivityTracker(holder, systemClock);
        var console = new DialogConsole(systemConsoleIO, statusRenderer, activityTracker);

        var listener = new CompositeEngineEventListener(List.of(
                new StatusEventListener(holder, systemClock), new MdcEventListener(), new LoggingEventListener()));
        var ports = new EnginePorts(
                stageExecutor(console, interactiveMode, holder),
                filesExistCheckRunner,
                shellCommandCheckRunner,
                new InteractiveExternalCheckClient(console),
                judgeVoter(console, interactiveMode),
                listener,
                attemptPersistence,
                systemClock,
                threadSleeper);

        var loop = new RunnerOutcomeLoop(new Engine(), console, java.time.Clock.systemUTC());
        return new Run(loop, ports, holder);
    }

    /**
     * Selects the stage executor: the interactive console adapter when {@code interactiveMode}
     * is {@code ALL} or {@code EXECUTOR_ONLY} (FR10, design D6), else the manifest-driven CLI
     * adapter wired with the renderer + status-enricher composite progress listener (task 9.4,
     * FR7, D10) bound to {@code holder}.
     */
    private StageExecutor stageExecutor(
            DialogConsole console, RunArguments.InteractiveMode interactiveMode, StatusSnapshotHolder holder) {
        return switch (interactiveMode) {
            case ALL, EXECUTOR_ONLY -> new InteractiveStageExecutor(console, new StageBriefing());
            case NONE, JUDGE_ONLY ->
                new CliStageExecutor(factoryProperties, systemClock, executorProgressListener(holder));
        };
    }

    /**
     * Selects the judge voter: the interactive console adapter when {@code interactiveMode} is
     * {@code ALL} or {@code JUDGE_ONLY} (FR10, design D6), else the manifest-driven CLI adapter
     * wired with the shared renderer alone (task 9.4, design D10): a judge round runs under the
     * verifying activity, so the executor-only status enricher is deliberately left out.
     */
    private JudgeVoter judgeVoter(DialogConsole console, RunArguments.InteractiveMode interactiveMode) {
        return switch (interactiveMode) {
            case ALL, JUDGE_ONLY -> new InteractiveJudgeVoter(console);
            case NONE, EXECUTOR_ONLY ->
                new CliJudgeVoter(factoryProperties, systemClock, new LoggingAgentProgressListener());
        };
    }

    /**
     * Builds the executor's progress listener (task 9.4, design D10): the shared {@link
     * LoggingAgentProgressListener} renderer plus an {@link AgentActivityEnricher} bound to {@code
     * holder}, fanned out via {@link CompositeAgentProgressListener}.
     */
    private static AgentProgressListener executorProgressListener(StatusSnapshotHolder holder) {
        return new CompositeAgentProgressListener(
                List.of(new LoggingAgentProgressListener(), new AgentActivityEnricher(holder)));
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
