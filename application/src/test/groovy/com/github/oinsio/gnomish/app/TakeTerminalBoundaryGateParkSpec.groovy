package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunPieces
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortFuse
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR11 of make-checkpoint-gate-durable (task 4.4, kill window "after the round commit, before
 * the park commit"): both {@code take} terminal boundaries — {@link TakeEngineExecution} and {@link
 * TakeContainerEngineExecution} — handed a state at a gate receive {@code Paused(stage)} from the
 * Engine with no execution or persistence port invoked, record the park's durable intent through the
 * repository exactly once (its {@code recordOutcome} is a content no-op on a tip already carrying the
 * same park: {@code RecordOutcomeLostParkSpec}), then park the tracker with the report carrying
 * {@link TerminalOutcomeRender#checkpointLine}, then write the receipt.
 *
 * <p>Driven through ports only, over the real {@code Engine}.
 */
class TakeTerminalBoundaryGateParkSpec extends Specification implements RunChainFakes {

    /** The task's worktree: the host boundary opens a directory workspace on it, so it must exist. */
    @TempDir
    Path worktree

    TaskState gated = CheckpointMechanicsFixtures.gatedAt('build')
    ScriptedExecutor executor = new ScriptedExecutor([completedRound()])
    InMemoryAttemptPersistence persistence = new InMemoryAttemptPersistence()
    Tracker tracker = Mock(Tracker)

    def setup() {
        tracker.fetchTask(_) >> heldByUs()
    }

    private TakeOrder order() {
        takeOrder(heldByUs(), tracker, runOrder(CheckpointMechanicsFixtures.gatedPipeline()))
    }

    /** The slot's outcome dispatch both twins end on (design D22 of supervise-daemon-loops-and-embed-dashboard). */
    private TakeOutcomeDispatch dispatch() {
        new TakeOutcomeDispatch(VirtualTimeRetries.terminalWrite(), new AbortFuse(new AbortHandler(tracker, FIXED_CLOCK), 3))
    }

    /** The Engine's answer for the gate: the stage that passed, and the recorded state unchanged. */
    private boolean isGatePark(TaskOutcome outcome) {
        outcome instanceof TaskOutcome.Paused && outcome.passedStage() == 'build' && outcome.finalState() == gated
    }

    private static boolean carriesCheckpoint(String report) {
        report.contains(TerminalOutcomeRender.checkpointLine('build'))
    }

    def "FR2, FR11: TakeEngineExecution records the park of a gate once, then parks the tracker, then writes the receipt"() {
        given:
        def lifecycleStore = Mock(TaskLifecycleStore)
        def branches = Mock(TaskBranchGit)
        def store = Stub(TaskStoreGit) {
            taskRepository(CLONE) >> lifecycleStore
            attemptPersistence(worktree, 'PROJ-1') >> persistence
        }
        def git = new TaskGit(store, branches, Mock(TaskWorktreeGit), new ClaimEpochBook())
        def bootstrap = new ResumeBootstrap('PROJ-1', CheckpointMechanicsFixtures.context(), null, null, worktree,
                'gnomish/PROJ-1', 'abc123', false, BasePin.UNPINNED)
        def execution = new TakeEngineExecution(assemblyRunning(executor), git, CLONE, dispatch(), [], new ClaimLossFlag(),
        LawBinding.atRevision(CLONE_DIR, GitObjects.HEAD))

        when:
        def result = execution.run(order(), bootstrap, CheckpointMechanicsFixtures.context(), gated)

        then: 'the durable intent: one park record owing the tracker write, then the delivery fence'
        1 * lifecycleStore.recordOutcome('PROJ-1', {
            isGatePark(it)
        }, TrackerWrite.OWED)
        1 * branches.fenceParkDelivery(CLONE_DIR, 'PROJ-1') >> new ParkDeliveryVerdict.Delivered()

        then: 'the effect, rendered through the one checkpoint sentence'
        1 * tracker.park(REF, ParkReason.CHECKPOINT, { carriesCheckpoint(it) })

        then: 'the receipt'
        1 * lifecycleStore.confirmTerminalWrite('PROJ-1')
        0 * lifecycleStore.finishCleanup(_)

        and: 'no stage ran and nothing was persisted'
        executor.requests.isEmpty()
        persistence.entries.isEmpty()
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT
    }

    def "FR2, FR11: TakeContainerEngineExecution records the park of a gate once, then parks the tracker, then writes the receipt"() {
        given:
        def support = Mock(SandboxRunSupport) {
            persistence() >> persistence
            workspace() >> new FakeWorkspace()
            pieces(_) >> new SandboxRunPieces(null, null, null, null, null, null, null)
        }
        def execution = new TakeContainerEngineExecution(assemblyRunning(executor), dispatch(), [], new ClaimLossFlag(),
        LawBinding.atRevision(CLONE_DIR, GitObjects.HEAD))

        when:
        def result = execution.run(order(), support, CheckpointMechanicsFixtures.context(), gated, null)

        then: 'the box is kept, never disposed'
        1 * support.keepStopped()
        0 * support.completeAndDispose(_)

        then: 'the durable intent: one park record owing the tracker write'
        1 * support.recordPark({ isGatePark(it) }, TrackerWrite.OWED)

        then: 'the effect, rendered through the one checkpoint sentence'
        1 * tracker.park(REF, ParkReason.CHECKPOINT, { carriesCheckpoint(it) })

        then: 'the receipt'
        1 * support.confirmTerminalWrite()
        0 * support.finishCleanup()

        and: 'no stage ran and nothing was persisted'
        executor.requests.isEmpty()
        persistence.entries.isEmpty()
        (result as TakeResult.AwaitingHuman).reason() == ParkReason.CHECKPOINT
    }
}
