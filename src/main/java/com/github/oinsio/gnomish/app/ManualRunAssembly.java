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
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.status.CompositeEngineEventListener;
import com.github.oinsio.gnomish.status.ConsoleStatusRenderer;
import com.github.oinsio.gnomish.status.LoggingEventListener;
import com.github.oinsio.gnomish.status.MdcEventListener;
import com.github.oinsio.gnomish.status.SnapshotActivityTracker;
import com.github.oinsio.gnomish.status.StatusEventListener;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Builds the per-run collaborators {@link ManualRunRunner} needs once a {@link TaskContext} and
 * initial {@link TaskState} are known: the shared {@link DialogConsole}, the manifest-driven or
 * interactive executor/judge adapters, and the assembled {@link EnginePorts} (design D10). Extracted
 * from {@link ManualRunRunner} for file size.
 * <p>Exactly one {@link StatusSnapshotHolder} and one {@link DialogConsole} are built per {@link
 * #assemble} call (design D1): the holder feeds the {@link StatusEventListener} and the {@link
 * SnapshotActivityTracker} (via the console), which then backs every interactive port.
 * <p>The default {@link StageExecutor}/{@link JudgeVoter} pair is the real CLI adapter (every stage
 * is {@code agent-cli} by construction); {@code --interactive} (design D6) swaps one or both roles
 * back to the console adapters via {@link RunArguments.InteractiveMode}. Role selection and each
 * adapter's live-progress wiring (FR7, NFR-O1, UX1) are delegated to {@link ExecutorAdapterSelector}.
 * The external-check client stays the console adapter unconditionally (NG4 of add-agent-executor).
 * <p>An optional {@code extraListener} (default {@code null}) joins the run's {@link
 * EngineEventListener} composite (task 6.1 of add-claim-heartbeat): the {@code take} run enriches its
 * shared assembly via {@link #withExtraListener} to register its {@code HeartbeatProgress}; the plain
 * manual-run and git paths keep the {@code null} default and are unaffected.
 * <p>Implements FR7, FR10, NFR-O1, UX1, D6, D10 of add-agent-executor; D10 of add-manual-run; FR7
 * of add-git-workflow; FR1, FR11 of add-claim-heartbeat.
 */
// A final class, not a record: PIT's Gregor engine RUN_ERRORs (crashes its minion JVM) when mutating
// a record here — the JVMTI RedefineClasses restriction on record classes (hcoles/pitest#1285),
// test-independent, not a real coverage gap. This is a stateful assembly holder, never compared or
// hashed, so as a plain class its methods mutate and are killed normally by
// ManualRunAssemblyWiringSpec (M5).
final class ManualRunAssembly {

    private final SystemConsoleIO systemConsoleIO;
    private final FilesExistCheckRunner filesExistCheckRunner;
    private final ShellCommandCheckRunner shellCommandCheckRunner;
    private final SystemClock systemClock;
    private final ThreadSleeper threadSleeper;
    private final FactoryProperties factoryProperties;
    private final @Nullable EngineEventListener extraListener;

    ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties,
            @Nullable EngineEventListener extraListener) {
        this.systemConsoleIO = systemConsoleIO;
        this.filesExistCheckRunner = filesExistCheckRunner;
        this.shellCommandCheckRunner = shellCommandCheckRunner;
        this.systemClock = systemClock;
        this.threadSleeper = threadSleeper;
        this.factoryProperties = factoryProperties;
        this.extraListener = extraListener;
    }

    /**
     * The dominant construction: no extra engine listener (the plain manual-run and git paths).
     * Delegates to the canonical constructor with a {@code null} {@code extraListener}, so every
     * existing 6-argument call site is unaffected by the take run's added seam.
     */
    ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties) {
        this(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                systemClock,
                threadSleeper,
                factoryProperties,
                null);
    }

    /**
     * Returns a copy of this assembly that also fans every engine event into {@code listener} (task
     * 6.1, FR1 of add-claim-heartbeat): the {@code take} run calls this once per invocation to add its
     * per-run {@code HeartbeatProgress} without disturbing the shared assembly the manual-run reuses.
     * @param listener the additional listener to join the run's composite; never null
     * @return a new assembly identical but for the added listener; never null
     */
    ManualRunAssembly withExtraListener(EngineEventListener listener) {
        return new ManualRunAssembly(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                systemClock,
                threadSleeper,
                factoryProperties,
                listener);
    }

    /**
     * Builds the per-run {@link RunnerOutcomeLoop} and {@link EnginePorts} for one {@code gnomish run}
     * invocation. {@code attemptPersistence} is supplied by the caller, not fixed at construction
     * (design D8 of add-git-workflow): in-place mode passes the shared in-memory bean, git mode a
     * fresh git-backed persistence rooted at the task worktree.
     * <p>Implements FR7, FR10, NFR-O1, UX1, D6, D10 of add-agent-executor; D10 of add-manual-run; FR7
     * of add-git-workflow; FR11 of add-claim-heartbeat.
     * @param definition the loaded pipeline the run advances through; never null
     * @param context the synthesized task's identity; never null
     * @param initialState the synthesized task's initial state; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter (FR10, D6)
     * @param attemptPersistence the {@code AttemptPersistence} realization rounds commit through
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential env-var names
     *     (D17, NFR-S1 of add-tracker-port), threaded into both the CLI executor/judge adapters'
     *     {@code AgentProcessLauncher} and the command-check runner (via {@link
     *     ShellCommandCheckRunner#withCredentialScrub}, FR11 of add-claim-heartbeat) so a credential
     *     reaches neither the gnome nor a command check; empty for plain {@code gnomish run}
     * @return the outcome loop and the ports it drives; never null
     */
    Run assemble(
            PipelineDefinition definition,
            TaskContext context,
            TaskState initialState,
            RunArguments.InteractiveMode interactiveMode,
            AttemptPersistence attemptPersistence,
            List<String> credentialEnvVarsToScrub) {
        var holder = new StatusSnapshotHolder(
                initialState, AttemptLimitResolver.resolve(definition, initialState.position()));
        var statusRenderer = new ConsoleStatusRenderer(holder, context, new StatusTextRenderer());
        var activityTracker = new SnapshotActivityTracker(holder, systemClock);
        var console = new DialogConsole(systemConsoleIO, statusRenderer, activityTracker);

        var listeners = new ArrayList<EngineEventListener>(List.of(
                new StatusEventListener(holder, systemClock), new MdcEventListener(), new LoggingEventListener()));
        if (extraListener != null) {
            // Task 6.1 of add-claim-heartbeat: the take run's HeartbeatProgress observes the same
            // event stream so each beat carries a live stage/attempt line. Null on every other path.
            listeners.add(extraListener);
        }
        var listener = new CompositeEngineEventListener(listeners);
        var ports = new EnginePorts(
                ExecutorAdapterSelector.stageExecutor(
                        console, interactiveMode, holder, factoryProperties, systemClock, credentialEnvVarsToScrub),
                filesExistCheckRunner,
                shellCommandCheckRunner.withCredentialScrub(credentialEnvVarsToScrub),
                new InteractiveExternalCheckClient(console),
                ExecutorAdapterSelector.judgeVoter(
                        console, interactiveMode, factoryProperties, systemClock, credentialEnvVarsToScrub),
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
     * ResumeDialogConsoleFactory} for file size.
     * @param context the resumed task's identity and decisions, for the {@code status} meta-command
     * @param state the resumed task's current state, seeding the status snapshot
     * @return a fresh {@link DialogConsole} wired the same way {@link #assemble} wires its own
     */
    DialogConsole dialogConsole(TaskContext context, TaskState state) {
        return ResumeDialogConsoleFactory.build(systemConsoleIO, systemClock, context, state);
    }
}
