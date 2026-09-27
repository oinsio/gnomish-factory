package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.FactoryProperties;
import com.github.oinsio.gnomish.app.console.DialogConsole;
import com.github.oinsio.gnomish.app.console.SystemConsoleIO;
import com.github.oinsio.gnomish.app.port.agent.RoundEnvironmentSource;
import com.github.oinsio.gnomish.app.port.console.ConsoleIO;
import com.github.oinsio.gnomish.app.port.pipeline.BoundTaskTier;
import com.github.oinsio.gnomish.app.port.pipeline.PipelineSource;
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;
import com.github.oinsio.gnomish.domain.engine.port.ExternalCheckClient;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.engine.time.ThreadSleeper;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import com.github.oinsio.gnomish.sandbox.SandboxProperties;
import com.github.oinsio.gnomish.status.SummaryAccumulatorListener;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Holds the per-run collaborators {@link ManualRunRunner} needs once a {@link TaskContext} and
 * initial {@link TaskState} are known: the shared {@link DialogConsole} inputs, the {@link
 * CheckEquipment}, and the pipeline/sandbox properties (design D10). The actual port/console assembly is
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
// Null-marked explicitly (JSpecify): this module carries no package-info, and the application
// module's one does not reach this source root, so without the class-level marker every override
// here reads as unannotated against its null-marked port.
@NullMarked
public final class ManualRunAssembly implements RunAssembly {

    final SystemConsoleIO systemConsoleIO;
    /**
     * The console owner bound to standard error (FR5, FR6 of harden-untrusted-text-sinks): the
     * terminal error paths write through this one rather than the dialog console, and which
     * stream that is stays a decision of the composition root.
     */
    final ConsoleIO errorConsole;

    final CheckEquipment checks;
    final SystemClock systemClock;
    final ThreadSleeper threadSleeper;
    final FactoryProperties factoryProperties;
    final SandboxProperties sandboxProperties;
    final @Nullable EngineEventListener extraListener;
    final @Nullable SandboxRunPieces sandbox;
    // Identity by default (design D3 of wire-host-mid-round-push): consumers apply the decoration
    // unconditionally, so "no decoration" needs no null check and no mode conditional.
    final UnaryOperator<RoundEnvironmentSource> hostGitPush;
    // Attached per invocation by take/serve (FR13 of add-base-ref-resolution); a manual run never
    // reads a task tier by binding, so it stays unattached and bindTaskTier is never reached.
    final @Nullable PipelineSource pipelineSource;

    /**
     * The dominant construction: no extra engine listener, no sandbox pieces, no host-git
     * decoration and no pipeline source (the plain manual-run and git paths); every wither below
     * derives its copy from one of these.
     */
    ManualRunAssembly(
            SystemConsoleIO systemConsoleIO,
            ConsoleIO errorConsole,
            CheckEquipment checks,
            SystemClock systemClock,
            ThreadSleeper threadSleeper,
            FactoryProperties factoryProperties,
            SandboxProperties sandboxProperties) {
        this.systemConsoleIO = systemConsoleIO;
        this.errorConsole = errorConsole;
        this.checks = checks;
        this.systemClock = systemClock;
        this.threadSleeper = threadSleeper;
        this.factoryProperties = factoryProperties;
        this.sandboxProperties = sandboxProperties;
        this.extraListener = null;
        this.sandbox = null;
        this.hostGitPush = UnaryOperator.identity();
        this.pipelineSource = null;
    }

    /** Copy construction: {@code base}'s collaborators carried over, the four optional seams supplied. */
    private ManualRunAssembly(
            ManualRunAssembly base,
            @Nullable EngineEventListener extraListener,
            @Nullable SandboxRunPieces sandbox,
            UnaryOperator<RoundEnvironmentSource> hostGitPush,
            @Nullable PipelineSource pipelineSource) {
        this.systemConsoleIO = base.systemConsoleIO;
        this.errorConsole = base.errorConsole;
        this.checks = base.checks;
        this.systemClock = base.systemClock;
        this.threadSleeper = base.threadSleeper;
        this.factoryProperties = base.factoryProperties;
        this.sandboxProperties = base.sandboxProperties;
        this.extraListener = extraListener;
        this.sandbox = sandbox;
        this.hostGitPush = hostGitPush;
        this.pipelineSource = pipelineSource;
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
        return new ManualRunAssembly(this, listener, sandbox, hostGitPush, pipelineSource);
    }

    /**
     * Returns the copy every manual path runs on — in-place, git, container, and both resumes —
     * which also carries a fresh {@link SummaryAccumulatorListener} (FR3, design D3 of
     * harden-logging-observability). A manual run has no terminal {@code TakeResult} to map, so the
     * engine's own run bookend is where its summary comes from, which is one seam rather than five
     * entry points each remembering. The tracker-driven subcommands ({@code take}, {@code serve})
     * get the plain assembly: they emit the canonical summary from their terminal result, and a
     * run-level listener doing it too would state the same outcome twice.
     *
     * <p>The one place that derivation is spelled: both beans that hold a manual-run copy ({@link
     * ManualRunConfiguration#manualRunners}, {@link ManualRunConfiguration#manualRunDrive}) take it
     * from here. Each gets its own listener instance; one invocation runs one path, so exactly one
     * of them ever observes a run.
     *
     * @return a new assembly identical but for the added summary listener; never null
     */
    ManualRunAssembly withRunSummary() {
        return withExtraListener(new SummaryAccumulatorListener());
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
        return new ManualRunAssembly(this, extraListener, pieces, hostGitPush, pipelineSource);
    }

    /**
     * Returns a copy of this assembly whose host executor rounds are decorated by {@code
     * decoration} (FR1, FR3, design D3 of wire-host-mid-round-push) — the git-mode host control
     * flows attach the composition root's mid-round push operator here; see {@link
     * RunAssembly#withHostGitPush}. {@link ExecutorAdapterSelector} applies it only on the
     * host branch, so sandbox pieces win by construction.
     *
     * @param decoration the round-source decoration the composition root built; never null
     * @return a new assembly identical but for the decoration; never null
     */
    @Override
    public ManualRunAssembly withHostGitPush(UnaryOperator<RoundEnvironmentSource> decoration) {
        return new ManualRunAssembly(this, extraListener, sandbox, decoration, pipelineSource);
    }

    /**
     * Returns a copy of this assembly whose runs read their task tier through {@code source}
     * (FR13, design D14 of add-base-ref-resolution); see {@link RunAssembly#withPipelineSource}.
     *
     * @param source where a task's law is read from by binding; never null
     * @return a new assembly identical but for the attached source; never null
     */
    @Override
    public ManualRunAssembly withPipelineSource(PipelineSource source) {
        return new ManualRunAssembly(this, extraListener, sandbox, hostGitPush, source);
    }

    /**
     * Reads one task's tier of the law through the attached source; see {@link
     * RunAssembly#bindTaskTier}. Unattached is a wiring fault of the invocation, not a per-task
     * condition, so it refuses loudly instead of reading anything.
     */
    @Override
    public BoundTaskTier bindTaskTier(LawBinding lawBinding) throws IOException {
        if (pipelineSource == null) {
            throw new IllegalStateException(
                    "no PipelineSource attached to this assembly: take and serve attach the one their"
                            + " startup definition came from before any task tier is read");
        }
        return pipelineSource.bindTaskTier(lawBinding);
    }

    /**
     * Peels one law binding to its commit; see {@link RunAssembly#lawCommitOf}. Uses the same
     * {@link RunLaw} opening every assembled run uses, so the manual tier's branch start point and
     * its frozen law are one commit by construction (FR15, D12 of add-base-ref-resolution).
     */
    @Override
    public @Nullable ObjectId lawCommitOf(LawBinding lawBinding) {
        return RunLaw.open(lawBinding).lawCommit();
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
            RunOrder order,
            TaskContext context,
            TaskState initialState,
            AttemptPersistence attemptPersistence,
            List<String> credentialEnvVarsToScrub,
            LawBinding lawBinding) {
        return RunAssembler.assemble(
                this, order, context, initialState, attemptPersistence, credentialEnvVarsToScrub, lawBinding);
    }

    /**
     * Selects and pin-guards the run's {@link ExternalCheckClient}. Delegated to {@link
     * CheckEquipment#externalCheckClient}; package-private testing seam: specs inject a {@code
     * registry} of hand-built providers over a fake secrets provider.
     */
    ExternalCheckClient externalCheckClient(
            DialogConsole console, LawBinding lawBinding, Map<String, CheckClientFactory> registry) {
        return checks.externalCheckClient(console, RunLaw.open(lawBinding), CheckRunContext.none(), registry);
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
