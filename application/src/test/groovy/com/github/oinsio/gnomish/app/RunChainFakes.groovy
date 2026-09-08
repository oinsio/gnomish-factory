package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.console.DialogConsole
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.BaseRefGit
import com.github.oinsio.gnomish.app.port.git.BaseRefKind
import com.github.oinsio.gnomish.app.port.git.BaseRefreshOutcome
import com.github.oinsio.gnomish.app.port.git.ResumeBaseOutcome
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.pipeline.BoundTaskTier
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.baseref.BaseDefinition
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.RecordingEventListener
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedBuiltinCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedCommandCheckRunner
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExternalCheckClient
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedJudgeVoter
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.domain.engine.fake.VirtualSleeper
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.LoadOutcome
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.gitobjects.ObjectId
import com.github.oinsio.gnomish.status.StatusSnapshotHolder
import java.nio.file.Path

/**
 * The run chains' collaborators — take, git-mode and container-mode — built from PORT fakes only:
 * no git binary, no tracker HTTP, no Docker, no composition root (design D13(c) of
 * split-into-modules: "port-fake unit specs ... never through {@code ManualRunAssembly}").
 *
 * <p>Every class in those chains is {@code final} or a {@code record}, so none of them can be
 * mocked: a spec has to build the REAL object and steer it at the nearest port instead. That is what this trait supplies — the wiring is production wiring, only the edges
 * of the hexagon are scripted. The seam most scenarios here lean on is that a {@link Tracker}
 * answering {@code ClaimResult.Held} makes {@code TakeClaimAndWork#claimAndWork} refuse before any
 * git port is touched, so the decision under test can be driven without a working copy.
 */
trait RunChainFakes implements TaskRecordFakes, FactoryPropertiesFixture {

    static final Path CLONE_DIR = Path.of('/tmp/gnomish-clone')
    static final Path WORKTREES_ROOT = Path.of('/tmp/gnomish-worktrees')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    static final TaskRef REF = new TaskRef('github:o/r#1')
    /** The law commit the fake task-tier reads report — any well-formed id, never resolved. */
    static final ObjectId LAW_COMMIT = ObjectId.of('0123456789abcdef0123456789abcdef01234567')
    /**
     * The trusted tier a port-fake fresh-claim scenario resolves against when it doesn't care
     * about base policy specifics: no allowed bases, no configured default, and a default branch
     * name any {@link BaseRefGit} stub's {@code refresh} may accept unconditionally.
     */
    static final TrustedBaseContext DEFAULT_TRUSTED_BASE = new TrustedBaseContext(BaseDefinition.none(), 'main')

    /**
     * FR13 of add-base-ref-resolution: {@link TaskTierLaw#bind} reads the task tier through {@link
     * RunAssembly#bindTaskTier} before a fresh claim creates anything — a scenario that stubs
     * {@code RunAssembly} bare (a plain {@code Stub(RunAssembly)}, no interactions) and still
     * dispatches a real {@code Acquired} claim through the fresh-claim path needs this call
     * answered, or Spock's own default response for the unstubbed method fails constructing one
     * (the return type nests a sealed interface, which Spock cannot default-proxy). Feed this to
     * the site's own {@code Stub(RunAssembly) { bindTaskTier(_) >> ... }} interaction — a plain
     * value, not a Spock construct, since a trait's shared method runs outside the per-feature
     * scope Spock's mock controller requires.
     */
    BoundTaskTier boundTaskTier() {
        new BoundTaskTier(new LoadOutcome.Loaded(pipeline()), LAW_COMMIT)
    }

    /**
     * FR2, FR6 of add-base-ref-resolution: a fresh claim resolves and refreshes its base before
     * creating anything, so a scenario that dispatches a real Acquired claim through the
     * fresh-claim path needs a working {@link BaseRefGit} rather than {@link
     * BaseRefGit#UNWIRED} — this always refreshes cleanly, whatever ref resolution names. A plain
     * closure-backed implementation, not a Spock {@code Stub}: a trait's shared method runs
     * outside the per-feature scope Spock's mock controller requires.
     */
    BaseRefGit refreshingBaseRefGit() {
        [
            refresh: { Path cloneDir, String ref ->
                new BaseRefreshOutcome.Refreshed(ref, ref, BaseRefKind.BRANCH)
            },
            discoverDefaultBranch: { Path cloneDir ->
                throw new UnsupportedOperationException('never called on the claim path (FR13)')
            },
            resolveForResume: { Path cloneDir, String ref ->
                throw new UnsupportedOperationException('not exercised by a fresh-claim scenario')
            },
        ] as BaseRefGit
    }

    /**
     * FR12, D13 of add-base-ref-resolution: resume always resolves its pinned base ref now, so a
     * port-fake chain that reaches the resume path needs a working {@link BaseRefGit} rather than
     * {@link BaseRefGit#UNWIRED} — the resolved tip echoes the pinned ref back, which is exactly
     * today's placeholder SHA input. Shared by the host and container resume-routing specs, which
     * assign it to a field rather than calling it lazily: Spock's ordered {@code then:}
     * verification tracks every mock/stub invocation, and building the stub during {@code when:}
     * would misfile its background interaction into the ordered sequence.
     */
    BaseRefGit resumingBaseRefGit() {
        [
            refresh: { Path cloneDir, String ref ->
                throw new UnsupportedOperationException('not exercised by a resume scenario')
            },
            discoverDefaultBranch: { Path cloneDir ->
                throw new UnsupportedOperationException('not exercised by a resume scenario')
            },
            resolveForResume: { Path cloneDir, String ref ->
                new ResumeBaseOutcome.Bound(ref, ref)
            },
        ] as BaseRefGit
    }

    /** A tracker task in {@code state} — the one shape the three named inputs below differ within. */
    TrackerTask trackerTask(TrackerTaskState state, String taskId = 'PROJ-1') {
        new TrackerTask(REF, new TaskSnapshot(taskId, 'title', 'body'), state, AbortFacts.none(), false)
    }

    /** A {@code Ready} tracker task — the ordinary explicit-mode input. */
    TrackerTask readyTask(String taskId = 'PROJ-1') {
        trackerTask(new TrackerTaskState.Ready(), taskId)
    }

    /** A {@code Working} tracker task held by {@code holder} — the takeover gate's input. */
    TrackerTask workingTask(String holder, String taskId = 'PROJ-1') {
        trackerTask(new TrackerTaskState.Working(holder), taskId)
    }

    /** A one-stage pipeline: enough to satisfy the signatures, never actually run by these specs. */
    PipelineDefinition pipeline() {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.AGENT_CLI, 'model-x', [:]),
        'instructions.md', [], new AutonomyLimits(3), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    /** A one-stage pipeline whose single builtin check passes: one engine round, then Completed. */
    PipelineDefinition completingPipeline() {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ],
        new AutonomyLimits(1), AdvancementMode.AUTO)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    /**
     * A {@link RunAssembly} handing back the domain's scripted engine-port fakes — the one seam
     * that keeps a whole-run scenario a unit spec. The persistence the caller assembled with is
     * passed straight through, so the run's own revocation-checking wrapper still applies.
     */
    RunAssembly assemblyRunning(ScriptedExecutor executor, Verdict verdict = new Verdict.Pass(),
            List hostGitPushAttached = []) {
        def clock = new VirtualClock()
        // A hand-written fake rather than a Spock Stub: mock creation is only legal inside a
        // feature's own lifetime, and this is built by a trait helper.
        [
            assemble: { definition, context, state, interactiveMode, AttemptPersistence persistence, credentials, lawBinding ->
                def ports = new EnginePorts(executor, new ScriptedBuiltinCheckRunner([verdict]),
                new ScriptedCommandCheckRunner(), new ScriptedExternalCheckClient(),
                new ScriptedJudgeVoter(), new RecordingEventListener(),
                persistence, clock, new VirtualSleeper(clock))
                new Run(null, ports, new StatusSnapshotHolder(state as TaskState, 1))
            },
            dialogConsole: { context, state ->
                throw new UnsupportedOperationException('no console in this spec')
            },
            withExtraListener: { listener ->
                assemblyRunning(executor, verdict, hostGitPushAttached)
            },
            withSandbox: { pieces ->
                assemblyRunning(executor, verdict, hostGitPushAttached)
            },
            withHostGitPush: { decoration ->
                hostGitPushAttached << decoration
                assemblyRunning(executor, verdict, hostGitPushAttached)
            },
            withPipelineSource: { source ->
                assemblyRunning(executor, verdict, hostGitPushAttached)
            },
            // FR13 of add-base-ref-resolution: the task tier a fresh claim binds is the completing
            // pipeline these chains run, read at a fixed law commit.
            bindTaskTier: { binding ->
                new BoundTaskTier(new LoadOutcome.Loaded(completingPipeline()), LAW_COMMIT)
            },
        ] as RunAssembly
    }

    /**
     * The same fake, but carrying a REAL {@link RunnerOutcomeLoop} over a scripted console — for the
     * paths that drive the loop rather than the engine directly (the git-mode resume chain), and
     * whose dialogs therefore have to answer. {@code io} feeds those prompts in order, and a caller
     * that wants to assert what the dialog PRINTED passes its own and reads {@code io.printed}.
     */
    RunAssembly assemblyRunningLoop(ScriptedExecutor executor, ScriptedConsoleIO io = new ScriptedConsoleIO(['']),
            Verdict verdict = new Verdict.Pass(), List hostGitPushAttached = []) {
        def clock = new VirtualClock()
        def console = new DialogConsole(io, { json ->
            'unused'
        })
        def self = null
        self = [
            assemble: { definition, context, state, interactiveMode, AttemptPersistence persistence, credentials, lawBinding ->
                def ports = new EnginePorts(executor, new ScriptedBuiltinCheckRunner([verdict]),
                new ScriptedCommandCheckRunner(), new ScriptedExternalCheckClient(),
                new ScriptedJudgeVoter(), new RecordingEventListener(),
                persistence, clock, new VirtualSleeper(clock))
                new Run(new RunnerOutcomeLoop(new Engine(), console, FIXED_CLOCK), ports,
                        new StatusSnapshotHolder(state as TaskState, 1))
            },
            dialogConsole: { context, state -> console },
            withExtraListener: { listener -> self },
            withSandbox: { pieces -> self },
            withHostGitPush: { decoration ->
                hostGitPushAttached << decoration
                self
            },
            withPipelineSource: { source -> self },
            bindTaskTier: { binding ->
                new BoundTaskTier(new LoadOutcome.Loaded(completingPipeline()), LAW_COMMIT)
            },
        ] as RunAssembly
        return self
    }

    /** One round the executor reports as completed. */
    ExecutionResult.Completed completedRound(String taskId = 'PROJ-1') {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey(taskId, 'build', 0), []), [])
    }

    /** The task as the tracker reports it mid-run: {@code Working}, held by THIS instance — not revoked. */
    TrackerTask heldByUs(String taskId = 'PROJ-1') {
        trackerTask(new TrackerTaskState.Working(INSTANCE.value()), taskId)
    }

    /**
     * A real {@link TakeClaimAndWork} over the given git ports and tracker. Its resume chain is
     * real too — {@code TakeResumeRunner} / {@code TakeDispositionResume} / {@code
     * TakeDecisionResume} — so a scenario that does reach a resume drives production wiring.
     */
    TakeClaimAndWork claimAndWork(TaskGit git, Tracker tracker, RunAssembly assembly,
            ClaimBeat beat = ClaimBeat.NONE, ClaimLossFlag claimLossFlag = new ClaimLossFlag(),
            Path root = WORKTREES_ROOT, ClaimEpochBook epochs = new ClaimEpochBook(),
            TrustedBaseContext trustedBase = DEFAULT_TRUSTED_BASE) {
        TakeClaimAndWorkFactory.forSlot(
                assembly, git, root, 'taskId',
                new AbortHandler(tracker, FIXED_CLOCK), 3, [], beat, claimLossFlag, ContainerTakeSupport.hostOnly(),
                epochs, trustedBase)
    }
}
