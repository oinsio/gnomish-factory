package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.FactoryProperties
import com.github.oinsio.gnomish.app.console.DialogConsole
import com.github.oinsio.gnomish.app.lease.ClaimBeat
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskRecord
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.InstanceId
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.Decision
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.EnginePorts
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
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
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import com.github.oinsio.gnomish.status.StatusSnapshotHolder
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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
trait RunChainFakes {

    static final Instant NOW = Instant.parse('2026-08-14T12:00:00Z')
    static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC)
    static final Path CLONE_DIR = Path.of('/tmp/gnomish-clone')
    static final Path WORKTREES_ROOT = Path.of('/tmp/gnomish-worktrees')
    static final InstanceId INSTANCE = new InstanceId('gnomish', 'ab12cd')
    static final TaskRef REF = new TaskRef('github:o/r#1')

    /**
     * The factory properties a spec needs to name an instance, with everything else defaulted —
     * the same builder {@code :bootstrap}'s {@code AppAssemblyFixture} offers, carried here so a
     * spec that needs ONLY this does not have to reach a composition-root fixture for it.
     */
    FactoryProperties testProperties(Map overrides = [:]) {
        new FactoryProperties(
                overrides.getOrDefault('instanceName', 'test-instance') as String,
                overrides['agentCliBinary'] as String,
                overrides['agentCliEnvPassthrough'] as List<String>,
                overrides['tracker'] as FactoryProperties.Tracker,
                overrides['check'] as FactoryProperties.Check)
    }

    /** A {@code Ready} tracker task — the ordinary explicit-mode input. */
    TrackerTask readyTask(String taskId = 'PROJ-1') {
        new TrackerTask(REF, new TaskSnapshot(taskId, 'title', 'body'), new TrackerTaskState.Ready(),
                AbortFacts.none(), false)
    }

    /** A {@code Working} tracker task held by {@code holder} — the takeover gate's input. */
    TrackerTask workingTask(String holder, String taskId = 'PROJ-1') {
        new TrackerTask(REF, new TaskSnapshot(taskId, 'title', 'body'), new TrackerTaskState.Working(holder),
                AbortFacts.none(), false)
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
    RunAssembly assemblyRunning(ScriptedExecutor executor, Verdict verdict = new Verdict.Pass()) {
        def clock = new VirtualClock()
        // A hand-written fake rather than a Spock Stub: mock creation is only legal inside a
        // feature's own lifetime, and this is built by a trait helper.
        [
            assemble: { definition, context, state, interactiveMode, persistence, credentials, cloneDir ->
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
                assemblyRunning(executor, verdict)
            },
            withSandbox: { pieces -> assemblyRunning(executor, verdict) },
        ] as RunAssembly
    }

    /**
     * The same fake, but carrying a REAL {@link RunnerOutcomeLoop} over a scripted console — for the
     * paths that drive the loop rather than the engine directly (the git-mode resume chain), and
     * whose dialogs therefore have to answer. {@code io} feeds those prompts in order, and a caller
     * that wants to assert what the dialog PRINTED passes its own and reads {@code io.printed}.
     */
    RunAssembly assemblyRunningLoop(ScriptedExecutor executor, ScriptedConsoleIO io = new ScriptedConsoleIO(['']),
            Verdict verdict = new Verdict.Pass()) {
        def clock = new VirtualClock()
        def console = new DialogConsole(io, { json ->
            'unused'
        })
        def self = null
        self = [
            assemble: { definition, context, state, interactiveMode, persistence, credentials, cloneDir ->
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
        ] as RunAssembly
        return self
    }

    /** One round the executor reports as completed. */
    ExecutionResult.Completed completedRound(String taskId = 'PROJ-1') {
        new ExecutionResult.Completed(ExecutorUsage.none(), new ToolTrace(new AttemptKey(taskId, 'build', 0), []))
    }

    /** The {@code task.json} of a branch with no recorded outcome yet. */
    TaskRecord freshRecord(String taskId = 'PROJ-1') {
        new TaskRecord(new TaskContext(taskId, 'title', 'body', List.<Decision> of()),
                'base-sha', NOW, null, null, false)
    }

    /** The task as the tracker reports it mid-run: {@code Working}, held by THIS instance — not revoked. */
    TrackerTask heldByUs(String taskId = 'PROJ-1') {
        new TrackerTask(REF, new TaskSnapshot(taskId, 'title', 'body'),
                new TrackerTaskState.Working(INSTANCE.value()), AbortFacts.none(), false)
    }

    /**
     * A real {@link TakeClaimAndWork} over the given git ports and tracker. Its resume chain is
     * real too — {@code TakeResumeRunner} / {@code TakeDispositionResume} / {@code
     * TakeDecisionResume} — so a scenario that does reach a resume drives production wiring.
     */
    TakeClaimAndWork claimAndWork(TaskGit git, Tracker tracker, RunAssembly assembly,
            ClaimBeat beat = ClaimBeat.NONE, ClaimLossFlag claimLossFlag = new ClaimLossFlag(),
            Path root = WORKTREES_ROOT) {
        TakeClaimAndWorkFactory.forSlot(
                assembly, git, root, 'taskId',
                new AbortHandler(tracker, FIXED_CLOCK), 3, [], beat, claimLossFlag)
    }
}
