package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ExecutionResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.port.Workspace
import com.github.oinsio.gnomish.domain.pipeline.AdvancementMode
import com.github.oinsio.gnomish.domain.pipeline.AutonomyLimits
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck
import spock.lang.Specification

/**
 * FR1 of add-serve-sandbox-lifecycle: {@link TakeContainerEngineExecution} settles the D19
 * terminal boundary itself (unlike {@link ContainerTerminalDrive}, which leans on {@code
 * RunnerOutcomeLoop}'s dialog-driven looping) — asserts, per {@link
 * com.github.oinsio.gnomish.domain.engine.TaskOutcome} category, which of {@link
 * SandboxRunSupport#completeAndDispose}/{@link SandboxRunSupport#recordAborted}/{@link
 * SandboxRunSupport#keepStopped} is called, and that a round-boundary revocation short-circuits
 * the terminal boundary entirely in favor of {@link SandboxRunSupport#revocationSalvageAndPush}.
 *
 * <p>Implements FR1 of add-serve-sandbox-lifecycle; FR9, FR12, FR13, FR15, FR18, D2, D3, D19 of
 * add-tracker-port and add-sandbox-core.
 */
class TakeContainerEngineExecutionSpec extends Specification implements RunChainFakes {

    private static Workspace workspace() {
        {} as Workspace
    }

    private ExecutionResult.DecisionNeeded decisionRound(String taskId = 'PROJ-1') {
        new ExecutionResult.DecisionNeeded(
                'which way?', [], ExecutorUsage.none(), new ToolTrace(new AttemptKey(taskId, 'build', 0), []), [])
    }

    private static PipelineDefinition manualCheckpointPipeline() {
        def stage = new StageDefinition('build', 'purpose', [], [],
        new StageDefinition.Executor(ExecutorType.API, 'model', [:]),
        'instructions.md', [
            new VerifyCheck.Builtin('files_exist', [:])
        ],
        new AutonomyLimits(1), AdvancementMode.MANUAL)
        new PipelineDefinition('1', new AutonomyLimits(3), [stage])
    }

    private TakeContainerEngineExecution execution(RunAssembly assembly, Tracker tracker) {
        new TakeContainerEngineExecution(
                assembly, new AbortHandler(tracker, FIXED_CLOCK), 3, [], new ClaimLossFlag(), CLONE_DIR)
    }

    // FR18, D19: a fresh Completed outcome disposes the environment and finishes the tracker for
    // real — never keepStopped/recordAborted.
    def "a Completed outcome disposes the environment and finishes the tracker"() {
        given:
        def tracker = Mock(Tracker)
        tracker.fetchTask(_) >> heldByUs()
        def support = Mock(SandboxRunSupport) {
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> workspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }

        when:
        def result = execution(assemblyRunning(new ScriptedExecutor([completedRound()])), tracker).run(
        support, completingPipeline(), trackerContext(), trackerState(), RunArguments.InteractiveMode.NONE,
        tracker, REF, INSTANCE, 'PROJ-1', null)

        then:
        // NFR-P1 of add-serve-sandbox-lifecycle: the sweep never runs on the claim (slot) path —
        // `take` sweeps once at startup with a real liveness verdict and `serve` on its own tick,
        // so a third, verdict-less pass here would only ever touch another session's manual
        // objects, outside the daemon's ledger, while a slot waits on it.
        0 * support.sweepOrphans()
        1 * support.restoreDenialCursor()
        1 * support.completeAndDispose(_)
        0 * support.keepStopped()
        0 * support.recordAborted(_)
        0 * support.revocationSalvageAndPush(_)
        1 * tracker.finish(REF, _)
        result instanceof TakeResult.Delivered
    }

    // FR13, D12, D19: a fresh Escalated outcome (a gnome-initiated decision) keeps the environment
    // stopped and parks the tracker for real with ESCALATION.
    def "an Escalated outcome keeps the environment stopped and parks the tracker"() {
        given:
        def tracker = Mock(Tracker)
        tracker.fetchTask(_) >> heldByUs()
        def support = Mock(SandboxRunSupport) {
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> workspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }

        when:
        def result = execution(assemblyRunning(new ScriptedExecutor([decisionRound()])), tracker).run(
        support, completingPipeline(), trackerContext(), trackerState(), RunArguments.InteractiveMode.NONE,
        tracker, REF, INSTANCE, 'PROJ-1', null)

        then:
        1 * support.keepStopped()
        0 * support.completeAndDispose(_)
        0 * support.recordAborted(_)
        1 * tracker.park(REF, ParkReason.ESCALATION, _)
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.ESCALATION
    }

    // FR13, FR18, D12, D19: a fresh Paused (manual checkpoint) outcome keeps the environment
    // stopped and parks the tracker for real with CHECKPOINT.
    def "a Paused checkpoint outcome keeps the environment stopped and parks the tracker"() {
        given:
        def tracker = Mock(Tracker)
        tracker.fetchTask(_) >> heldByUs()
        def support = Mock(SandboxRunSupport) {
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> workspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }

        when:
        def result = execution(assemblyRunning(new ScriptedExecutor([completedRound()])), tracker).run(
        support, manualCheckpointPipeline(), trackerContext(), trackerState(),
        RunArguments.InteractiveMode.NONE, tracker, REF, INSTANCE, 'PROJ-1', null)

        then:
        1 * support.keepStopped()
        0 * support.completeAndDispose(_)
        1 * tracker.park(REF, ParkReason.CHECKPOINT, _)
        result instanceof TakeResult.AwaitingHuman
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT
    }

    // FR14, D19: a broken round durability write is caught by the engine's own attempt journal and
    // surfaces as Aborted — the environment is recorded aborted AND kept stopped as evidence.
    def "an Aborted outcome records the abort and keeps the environment stopped"() {
        given:
        def tracker = Mock(Tracker)
        tracker.fetchTask(_) >> heldByUs()
        def support = Mock(SandboxRunSupport) {
            persistence() >> new InMemoryAttemptPersistence(failOnCall: 1)
            workspace() >> workspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }

        when:
        def result = execution(assemblyRunning(new ScriptedExecutor([completedRound()])), tracker).run(
        support, completingPipeline(), trackerContext(), trackerState(), RunArguments.InteractiveMode.NONE,
        tracker, REF, INSTANCE, 'PROJ-1', null)

        then:
        1 * support.recordAborted(_)
        1 * support.keepStopped()
        0 * support.completeAndDispose(_)
        0 * support.revocationSalvageAndPush(_)

        and: 'below the abort fuse the run is Aborted for retry, never parked as an infra escalation'
        result instanceof TakeResult.Aborted
        !(result instanceof TakeResult.AwaitingHuman)
    }

    // FR15, D2, D3: a round-boundary "no longer ours" check short-circuits the terminal boundary
    // entirely — salvage+push, a tracker note, and release, never dispose/keep/abort.
    def "a revoked claim salvages and pushes instead of settling any terminal boundary"() {
        given:
        def tracker = Mock(Tracker)
        // The round-boundary check inside RevocationCheckingAttemptPersistence reads this after
        // its delegate's persist succeeds: Gone means "no longer ours" — a revocation, not Aborted.
        tracker.fetchTask(_) >> trackerTask(new TrackerTaskState.Gone())
        def support = Mock(SandboxRunSupport) {
            persistence() >> new InMemoryAttemptPersistence()
            workspace() >> workspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }

        when:
        def result = execution(assemblyRunning(new ScriptedExecutor([completedRound()])), tracker).run(
        support, completingPipeline(), trackerContext(), trackerState(), RunArguments.InteractiveMode.NONE,
        tracker, REF, INSTANCE, 'PROJ-1', null)

        then:
        1 * support.revocationSalvageAndPush('PROJ-1')
        0 * support.completeAndDispose(_)
        0 * support.keepStopped()
        0 * support.recordAborted(_)
        1 * tracker.postNote(REF, _)
        1 * tracker.release(REF)
        result instanceof TakeResult.Revoked
    }

    private static com.github.oinsio.gnomish.domain.engine.TaskContext trackerContext(String taskId = 'PROJ-1') {
        new com.github.oinsio.gnomish.domain.engine.TaskContext(taskId, 'title', 'body', [])
    }

    private static com.github.oinsio.gnomish.domain.engine.TaskState trackerState() {
        com.github.oinsio.gnomish.domain.engine.TaskState.atStageStart('build')
    }
}
