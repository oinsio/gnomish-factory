package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory;
import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.console.SystemConsoleIO;
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Holds the per-run collaborators {@link ManualRunRunner} needs once a {@link TaskContext} and
 * initial {@link TaskState} are known: the shared {@link DialogConsole} inputs, the check
 * runners, and the pipeline/sandbox properties (design D10). The actual port/console assembly is
 * delegated to {@link RunAssembler}, extracted for file size. Exactly one {@link
 * com.github.oinsio.gnomish.status.StatusSnapshotHolder} and one {@link DialogConsole} are built
 * per {@link #assemble} call (design D1); the default {@link
 * com.github.oinsio.gnomish.domain.engine.port.StageExecutor}/{@link
 * com.github.oinsio.gnomish.domain.engine.port.JudgeVoter} pair is the real CLI adapter, with
 * {@code --interactive} (design D6) swapping one or both roles to the console adapters via {@link
 * RunArguments.InteractiveMode} (see {@link ExecutorAdapterSelector}).
 * <p>An optional {@code extraListener} (default {@code null}) joins the run's {@link
 * EngineEventListener} composite (task 6.1 of add-claim-heartbeat): the {@code take} run enriches its
 * shared assembly via {@link #withExtraListener} to register its {@code HeartbeatProgress}; the plain
 * manual-run and git paths keep the {@code null} default and are unaffected.
 * <p>The single realization of the {@link RunAssembly} port (task 4.4 of split-into-modules):
 * every adapter this class and {@link RunAssembler} name — the check runners, the console I/O, the
 * pipeline law reader, the external-check client factory — stops here, and the runners and {@code
 * take} steps above it see the port alone. Its constructors stay package-private, so building one
 * remains the composition root's privilege.
 * <p>Implements FR7, FR10, NFR-O1, UX1, D6, D10 of add-agent-executor; D10 of add-manual-run; FR7
 * of add-git-workflow; FR1, FR11 of add-claim-heartbeat; FR1, M2 of add-factory-serve.
 */
// A final class, not a record: PIT's Gregor engine RUN_ERRORs (crashes its minion JVM) when mutating
// a record here — the JVMTI RedefineClasses restriction on record classes (hcoles/pitest#1285),
// test-independent, not a real coverage gap. This is a stateful assembly holder, never compared or
// hashed, so as a plain class its methods mutate and are killed normally by
// ManualRunAssemblyWiringSpec (M5).
public final class ManualRunAssembly implements RunAssembly {

    final SystemConsoleIO systemConsoleIO;
    final FilesExistCheckRunner filesExistCheckRunner;
    final ShellCommandCheckRunner shellCommandCheckRunner;
    final GithubCheckClientFactory githubCheckClientFactory;
    final SystemClock systemClock;
    final ThreadSleeper threadSleeper;
    final FactoryProperties factoryProperties;
    final SandboxProperties sandboxProperties;
    final @Nullable EngineEventListener extraListener;
    final @Nullable SandboxRunPieces sandbox;

    private ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            GithubCheckClientFactory githubCheckClientFactory,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties,
            @Nullable EngineEventListener extraListener,
            @Nullable SandboxRunPieces sandbox) {
        this.systemConsoleIO = systemConsoleIO;
        this.filesExistCheckRunner = filesExistCheckRunner;
        this.shellCommandCheckRunner = shellCommandCheckRunner;
        this.githubCheckClientFactory = githubCheckClientFactory;
        this.systemClock = systemClock;
        this.threadSleeper = threadSleeper;
        this.factoryProperties = factoryProperties;
        this.sandboxProperties = sandboxProperties;
        this.extraListener = extraListener;
        this.sandbox = sandbox;
    }

    /**
     * The dominant construction: no extra engine listener and no sandbox pieces (the plain
     * manual-run and git paths). Delegates to the canonical constructor with both {@code null},
     * so every existing call site is unaffected by the take run's added seam or by sandbox mode.
     */
    ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            GithubCheckClientFactory githubCheckClientFactory,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties) {
        this(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                githubCheckClientFactory,
                systemClock,
                threadSleeper,
                factoryProperties,
                sandboxProperties,
                null,
                null);
    }

    /**
     * Returns a copy of this assembly that also fans every engine event into {@code listener} (task
     * 6.1, FR1 of add-claim-heartbeat): the {@code take} run calls this once per invocation to add its
     * per-run {@code HeartbeatProgress} without disturbing the shared assembly the manual-run reuses.
     * @param listener the additional listener to join the run's composite; never null
     * @return a new assembly identical but for the added listener; never null
     */
    @Override
    public ManualRunAssembly withExtraListener(EngineEventListener listener) {
        return new ManualRunAssembly(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                githubCheckClientFactory,
                systemClock,
                threadSleeper,
                factoryProperties,
                sandboxProperties,
                listener,
                sandbox);
    }

    /**
     * Returns a copy of this assembly whose runs execute in container mode through {@code
     * pieces} (the integration pass of add-sandbox-core): the CLI executor rounds run in the
     * leased box with snapshot-closed rounds, judge votes in fresh boxes, command checks per
     * their freshness knob, builtin checks against the attempt commit, and external checks
     * behind the delivery precondition. Host runs keep the {@code null} default and are
     * untouched (G4, D20).
     *
     * @param pieces the sandboxed-run adapter bundle; never null
     * @return a new assembly identical but for the sandbox pieces; never null
     */
    @Override
    public ManualRunAssembly withSandbox(SandboxRunPieces pieces) {
        return new ManualRunAssembly(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                githubCheckClientFactory,
                systemClock,
                threadSleeper,
                factoryProperties,
                sandboxProperties,
                extraListener,
                pieces);
    }

    /**
     * Builds the per-run {@link RunnerOutcomeLoop} and {@link com.github.oinsio.gnomish.domain.engine.EnginePorts}
     * for one {@code gnomish run} invocation. Delegated to {@link RunAssembler} for file size; see
     * its javadoc for the full parameter contract (FR7, FR10, NFR-O1, UX1, D6, D10 of
     * add-agent-executor; D10 of add-manual-run; FR7 of add-git-workflow; FR11 of
     * add-claim-heartbeat; D14 of add-sandbox-core).
     */
    @Override
    public Run assemble(
            PipelineDefinition definition,
            TaskContext context,
            TaskState initialState,
            RunArguments.InteractiveMode interactiveMode,
            AttemptPersistence attemptPersistence,
            List<String> credentialEnvVarsToScrub,
            Path lawSourceRoot) {
        return RunAssembler.assemble(
                this,
                definition,
                context,
                initialState,
                interactiveMode,
                attemptPersistence,
                credentialEnvVarsToScrub,
                lawSourceRoot);
    }

    /**
     * Selects and pin-guards the run's {@link ExternalCheckClient}. Delegated to {@link
     * RunAssembler#externalCheckClient} for file size; package-private testing seam: specs inject a
     * {@code checkClientFactory} with a fake secrets provider.
     */
    ExternalCheckClient externalCheckClient(
            DialogConsole console, Path lawSourceRoot, GithubCheckClientFactory checkClientFactory) {
        return RunAssembler.externalCheckClient(this, console, lawSourceRoot, checkClientFactory);
    }

    /**
     * Builds a standalone {@link DialogConsole} for a resume dialog that runs before any {@link
     * #assemble} call (design D9, task 4.7 of add-git-workflow), delegated to {@link
     * ResumeDialogConsoleFactory} for file size.
     * @param context the resumed task's identity and decisions, for the {@code status} meta-command
     * @param state the resumed task's current state, seeding the status snapshot
     * @return a fresh {@link DialogConsole} wired the same way {@link #assemble} wires its own
     */
    @Override
    public DialogConsole dialogConsole(TaskContext context, TaskState state) {
        return ResumeDialogConsoleFactory.build(systemConsoleIO, systemClock, context, state);
    }
}
