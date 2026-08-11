package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.SandboxProperties;
import com.github.oinsio.gnomish.adapter.check.ExternalCheckPinContributor;
import com.github.oinsio.gnomish.adapter.check.FilesExistCheckRunner;
import com.github.oinsio.gnomish.adapter.check.PinCheckedExternalCheckClient;
import com.github.oinsio.gnomish.adapter.check.ShellCommandCheckRunner;
import com.github.oinsio.gnomish.adapter.check.github.GithubCheckClientFactory;
import com.github.oinsio.gnomish.adapter.console.DialogConsole;
import com.github.oinsio.gnomish.adapter.console.InteractiveExternalCheckClient;
import com.github.oinsio.gnomish.adapter.console.SystemConsoleIO;
import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.adapter.engine.ThreadSleeper;
import com.github.oinsio.gnomish.adapter.environment.ChildEnvAllowlist;
import com.github.oinsio.gnomish.adapter.law.PipelineLawReader;
import com.github.oinsio.gnomish.domain.engine.Engine;
import com.github.oinsio.gnomish.domain.engine.EnginePorts;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.port.JudgeVoter;
import com.github.oinsio.gnomish.domain.engine.port.StageExecutor;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.gitobjects.GitObjects;
import com.github.oinsio.gnomish.status.CompositeEngineEventListener;
import com.github.oinsio.gnomish.status.ConsoleStatusRenderer;
import com.github.oinsio.gnomish.status.LoggingEventListener;
import com.github.oinsio.gnomish.status.MdcEventListener;
import com.github.oinsio.gnomish.status.SnapshotActivityTracker;
import com.github.oinsio.gnomish.status.StatusEventListener;
import com.github.oinsio.gnomish.status.StatusSnapshotHolder;
import com.github.oinsio.gnomish.status.StatusTextRenderer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
 * The external-check client is the GitHub Actions adapter when {@code factory.check.github.*} is
 * configured (FR26 of add-sandbox-core), the interactive console adapter otherwise; either is
 * wrapped by the pin-check guard (FR16, task 8.4 of add-sandbox-core).
 * <p>An optional {@code extraListener} (default {@code null}) joins the run's {@link
 * EngineEventListener} composite (task 6.1 of add-claim-heartbeat): the {@code take} run enriches its
 * shared assembly via {@link #withExtraListener} to register its {@code HeartbeatProgress}; the plain
 * manual-run and git paths keep the {@code null} default and are unaffected.
 * <p>Implements FR7, FR10, NFR-O1, UX1, D6, D10 of add-agent-executor; D10 of add-manual-run; FR7
 * of add-git-workflow; FR1, FR11 of add-claim-heartbeat.
 *
 * <p>The class itself (not its constructors or methods, which stay package-private — every call
 * site that builds or mutates one remains inside {@code app}) is {@code public} so {@code
 * com.github.oinsio.gnomish.app.serve.TakeSlotRunner} (task 4.3 of add-factory-serve) can hold and
 * pass through an already-built instance, mirroring {@link TakeBareAuto}'s own constructor
 * parameter of this type. Implements FR1, M2 of add-factory-serve.
 */
// A final class, not a record: PIT's Gregor engine RUN_ERRORs (crashes its minion JVM) when mutating
// a record here — the JVMTI RedefineClasses restriction on record classes (hcoles/pitest#1285),
// test-independent, not a real coverage gap. This is a stateful assembly holder, never compared or
// hashed, so as a plain class its methods mutate and are killed normally by
// ManualRunAssemblyWiringSpec (M5).
public final class ManualRunAssembly {

    private final SystemConsoleIO systemConsoleIO;
    private final FilesExistCheckRunner filesExistCheckRunner;
    private final ShellCommandCheckRunner shellCommandCheckRunner;
    private final SystemClock systemClock;
    private final ThreadSleeper threadSleeper;
    private final FactoryProperties factoryProperties;
    private final SandboxProperties sandboxProperties;
    private final @Nullable EngineEventListener extraListener;
    private final @Nullable SandboxRunPieces sandbox;

    ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties,
            @Nullable EngineEventListener extraListener) {
        this(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                systemClock,
                threadSleeper,
                factoryProperties,
                sandboxProperties,
                extraListener,
                null);
    }

    private ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties,
            @Nullable EngineEventListener extraListener,
            @Nullable SandboxRunPieces sandbox) {
        this.systemConsoleIO = systemConsoleIO;
        this.filesExistCheckRunner = filesExistCheckRunner;
        this.shellCommandCheckRunner = shellCommandCheckRunner;
        this.systemClock = systemClock;
        this.threadSleeper = threadSleeper;
        this.factoryProperties = factoryProperties;
        this.sandboxProperties = sandboxProperties;
        this.extraListener = extraListener;
        this.sandbox = sandbox;
    }

    /**
     * The dominant construction: no extra engine listener (the plain manual-run and git paths).
     * Delegates to the canonical constructor with a {@code null} {@code extraListener}, so every
     * existing call site is unaffected by the take run's added seam.
     */
    ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            FilesExistCheckRunner filesExistCheckRunner,
            ShellCommandCheckRunner shellCommandCheckRunner,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties) {
        this(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                systemClock,
                threadSleeper,
                factoryProperties,
                sandboxProperties,
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
    ManualRunAssembly withSandbox(SandboxRunPieces pieces) {
        return new ManualRunAssembly(
                systemConsoleIO,
                filesExistCheckRunner,
                shellCommandCheckRunner,
                systemClock,
                threadSleeper,
                factoryProperties,
                sandboxProperties,
                extraListener,
                pieces);
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
     *     (D17, NFR-S1 of add-tracker-port), combined here with the operator's {@code
     *     factory.sandbox.env-passthrough} into the run's {@link ChildEnvAllowlist} (D6, FR9 of
     *     add-sandbox-core) — a credential name in passthrough is refused at this seam, and the CLI
     *     executor/judge adapters and the command-check runner all compose their child environments
     *     from the resulting positive allowlist, so a credential reaches neither the gnome nor a
     *     command check by construction; empty for plain {@code gnomish run}
     * @param lawSourceRoot the root the pipeline law is frozen from at invocation start (D14, FR19
     *     of add-sandbox-core), against which control-file and criteria references resolve — the
     *     same root the runtime has always resolved them against: the {@code --dir} workspace root
     *     in-place, the factory clone's working-tree root in git/take modes. In git/take modes this
     *     is the clone, never the gnome's per-task worktree, so a running task cannot rewrite its
     *     own instructions or acceptance criteria; in-place it is the workspace itself, and the read
     *     is frozen in memory so a later edit to the same file has no effect on the running task
     * @return the outcome loop and the ports it drives; never null
     */
    Run assemble(
            PipelineDefinition definition,
            TaskContext context,
            TaskState initialState,
            RunArguments.InteractiveMode interactiveMode,
            AttemptPersistence attemptPersistence,
            List<String> credentialEnvVarsToScrub,
            Path lawSourceRoot) {
        var law = PipelineLawReader.freeze(lawSourceRoot, definition);
        var holder = new StatusSnapshotHolder(
                initialState, AttemptLimitResolver.resolve(definition, initialState.position()));
        var statusRenderer = new ConsoleStatusRenderer(holder, context, new StatusTextRenderer());
        var activityTracker = new SnapshotActivityTracker(holder, systemClock);
        var console = new DialogConsole(systemConsoleIO, statusRenderer, activityTracker);

        List<EngineEventListener> listeners = new ArrayList<>(List.of(
                new StatusEventListener(holder, systemClock), new MdcEventListener(), new LoggingEventListener()));
        if (extraListener != null) {
            // Task 6.1 of add-claim-heartbeat: the take run's HeartbeatProgress observes the same
            // event stream so each beat carries a live stage/attempt line. Null on every other path.
            listeners.add(extraListener);
        }
        // The run's layered child-environment allowlist (D6, FR9 of add-sandbox-core): operator
        // passthrough plus the declared credential names — the tracker's, and the external-check
        // token when that adapter is configured (FR26) — validated here, before any dialog, so a
        // credential name in passthrough fails the run at assembly time.
        var childEnv =
                ChildEnvAllowlist.of(sandboxProperties.envPassthrough(), credentialNames(credentialEnvVarsToScrub));
        var listener = new CompositeEngineEventListener(listeners);
        var builtinRunner = sandbox == null
                ? filesExistCheckRunner
                : filesExistCheckRunner.withAttemptReader(sandbox.attemptReader());
        var commandRunner = shellCommandCheckRunner.withChildEnv(childEnv);
        if (sandbox != null) {
            commandRunner = commandRunner.withEnvironments(sandbox.checkEnvironments());
        }
        var ports = new EnginePorts(
                ExecutorAdapterSelector.stageExecutor(
                        console, interactiveMode, holder, factoryProperties, systemClock, childEnv, law, sandbox),
                builtinRunner,
                commandRunner,
                externalCheckClient(console, lawSourceRoot, new GithubCheckClientFactory()),
                ExecutorAdapterSelector.judgeVoter(
                        console, interactiveMode, factoryProperties, systemClock, childEnv, law, sandbox),
                listener,
                attemptPersistence,
                systemClock,
                threadSleeper,
                sandbox == null
                        ? com.github.oinsio.gnomish.domain.engine.port.AttemptDelivery.assumedDelivered()
                        : sandbox.attemptDelivery());

        var loop = new RunnerOutcomeLoop(new Engine(), console, java.time.Clock.systemUTC());
        return new Run(loop, ports, holder);
    }

    /**
     * The declared credential names the run's {@link ChildEnvAllowlist} refuses in passthrough and
     * scrubs from every composed child environment: the active tracker adapter's (supplied by the
     * caller), plus {@code GNOMISH_GITHUB_ACTIONS_TOKEN} whenever the GitHub Actions external-check
     * adapter is configured — the adapter declares its credential name so it can never be admitted
     * into a child environment, matching the tracker token's treatment (FR26, NFR-S1 of
     * add-sandbox-core).
     */
    private List<String> credentialNames(List<String> credentialEnvVarsToScrub) {
        if (!factoryProperties.check().github().configured()) {
            return credentialEnvVarsToScrub;
        }
        var names = new ArrayList<>(credentialEnvVarsToScrub);
        names.add(GithubCheckClientFactory.TOKEN_ENV_VAR);
        return names;
    }

    /**
     * Selects and pin-guards the run's {@link ExternalCheckClient} (task 8.4 of add-sandbox-core):
     * with {@code factory.check.github.*} configured, the GitHub Actions adapter built by {@code
     * checkClientFactory} (base URL and repo from config, token via the {@code SecretsProvider} —
     * a missing token fails the assembly naming the secret, FR26); otherwise the interactive
     * console client, which contributes no pin paths. Either way the client is wrapped in the
     * {@link PinCheckedExternalCheckClient} (FR16, D10) comparing against the law source clone:
     * {@code lawSourceRoot} is the factory clone checked out at the base branch in git/take modes
     * (D14), so {@code HEAD} there <em>is</em> the bound base branch; the in-place mode's
     * workspace may not be a git repository at all, in which case a check that declares pin paths
     * degrades fail-closed to CannotVerify while a pinless interactive check passes vacuously.
     * Package-private testing seam: specs inject a {@code checkClientFactory} with a fake secrets
     * provider.
     */
    ExternalCheckClient externalCheckClient(
            DialogConsole console, Path lawSourceRoot, GithubCheckClientFactory checkClientFactory) {
        var github = factoryProperties.check().github();
        ExternalCheckClient client;
        ExternalCheckPinContributor contributor;
        if (github.configured()) {
            client = checkClientFactory.create(
                    Objects.requireNonNull(github.apiUrl()), Objects.requireNonNull(github.repo()));
            contributor = checkClientFactory.pinContributor();
        } else {
            client = new InteractiveExternalCheckClient(console);
            contributor = ExternalCheckPinContributor.none();
        }
        var gitObjects = GitObjects.open(
                lawSourceRoot.resolve(".git"), Path.of(Objects.requireNonNull(System.getProperty("java.io.tmpdir"))));
        return new PinCheckedExternalCheckClient(client, contributor, gitObjects, "HEAD");
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
