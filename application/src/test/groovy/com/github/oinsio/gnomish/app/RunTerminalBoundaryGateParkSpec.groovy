package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.git.TaskWorktreePath
import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.BasePin
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.fake.FakeWorkspace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.gitobjects.GitObjects
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR11 of make-checkpoint-gate-durable (task 4.4, kill window "after the round commit, before
 * the park commit"): every manual-run terminal boundary — {@link GitModeRunner#run}, the shared tail
 * of {@link GitResumeContinuation}, {@link ContainerTerminalDrive#run} — handed a state at a gate
 * receives {@code Paused(stage)} from the Engine with no execution or persistence port invoked,
 * records that park through the repository exactly once (whose {@code recordOutcome} is a content
 * no-op on a tip that already carries it: {@code RecordOutcomeLostParkSpec}), renders the stop
 * through {@link TerminalOutcomeRender#paused}, and leaves by the checkpoint exit.
 *
 * <p>Driven through ports only, over a real {@code RunnerOutcomeLoop} and {@code Engine}.
 */
class RunTerminalBoundaryGateParkSpec extends Specification implements RunChainFakes {

    @TempDir
    Path tempDir

    Path cloneDir

    TaskState gated = CheckpointMechanicsFixtures.gatedAt('build')
    ScriptedExecutor executor = new ScriptedExecutor([completedRound()])
    InMemoryAttemptPersistence persistence = new InMemoryAttemptPersistence()
    ScriptedConsoleIO io = new ScriptedConsoleIO()

    TaskLifecycleStore lifecycleStore = Mock(TaskLifecycleStore)
    TaskWorktreeGit worktrees = Mock(TaskWorktreeGit)
    TaskStoreGit store = Stub(TaskStoreGit)

    def setup() {
        cloneDir = tempDir.resolve('my-project')
        Files.createDirectories(cloneDir)
        store.taskRepository(_) >> lifecycleStore
        store.attemptPersistence(_, _) >> persistence
    }

    private TaskGit git() {
        new TaskGit(store, Stub(TaskBranchGit), worktrees, new ClaimEpochBook())
    }

    private RunOrder order() {
        new RunOrder(cloneDir, null, CheckpointMechanicsFixtures.gatedPipeline(), false)
    }

    /** The checkpoint render with the return path naming this clone and task, as the operator reads it. */
    private String pausedRender() {
        TerminalOutcomeRender.paused('build', new TerminalOutcomeRender.ReturnPath(cloneDir, 'PROJ-1'))
    }

    /** The Engine's answer for the gate: the stage that passed, and the recorded state unchanged. */
    private boolean isGatePark(TaskOutcome outcome) {
        outcome instanceof TaskOutcome.Paused && outcome.passedStage() == 'build' && outcome.finalState() == gated
    }

    def "FR2, FR11: GitModeRunner records the park of a gate once, renders it and exits by the checkpoint"() {
        given:
        def registered = RegisteredCloneFixture.unregistered(tempDir.resolve('home'), cloneDir)
        Files.createDirectories(TaskWorktreePath.resolve(registered, 'PROJ-1'))
        def runner = new GitModeRunner(assemblyRunningLoop(executor, io), git(), registered, new ScriptedConsoleIO())

        when:
        runner.run(order(), CheckpointMechanicsFixtures.context(), gated)

        then:
        def stop = thrown(RunParkedException)
        stop.checkpoint()

        and: 'one park record, owing no tracker write; the worktree kept by the park disposal'
        1 * lifecycleStore.recordOutcome('PROJ-1', {
            isGatePark(it)
        }, TrackerWrite.NONE)
        1 * worktrees.cleanUp(cloneDir, _, { isGatePark(it) })
        0 * lifecycleStore.finishCleanup(_)

        and: 'no stage ran, nothing persisted, and the stop reads through the one render'
        executor.requests.isEmpty()
        persistence.entries.isEmpty()
        io.printed.join('').contains(pausedRender())
    }

    def "FR2, FR11: GitResumeContinuation's terminal boundary records the park of a gate once and exits by the checkpoint"() {
        given:
        Path worktree = Files.createDirectories(tempDir.resolve('worktree'))
        worktrees.salvage(worktree) >> Stub(WorktreeSalvager)
        def bootstrap = new ResumeBootstrap('PROJ-1', CheckpointMechanicsFixtures.context(), null, null, worktree,
                'gnomish/PROJ-1', 'abc123', false, BasePin.UNPINNED)
        def continuation = new GitResumeContinuation(assemblyRunningLoop(executor, io), git(), lifecycleStore, cloneDir, bootstrap)

        when:
        continuation.resumeFromRecordedPosition(order(), gated)

        then:
        def stop = thrown(RunParkedException)
        stop.checkpoint()
        1 * lifecycleStore.recordOutcome('PROJ-1', {
            isGatePark(it)
        }, TrackerWrite.NONE)
        1 * worktrees.cleanUp(cloneDir, worktree, { isGatePark(it) })
        0 * lifecycleStore.finishCleanup(_)

        and:
        executor.requests.isEmpty()
        persistence.entries.isEmpty()
        io.printed.join('').contains(pausedRender())
    }

    def "FR2, FR11: ContainerTerminalDrive records the park of a gate once, keeps the box and exits by the checkpoint"() {
        given:
        def support = Mock(SandboxRunSupport) {
            persistence() >> persistence
            workspace() >> new FakeWorkspace()
            pieces(_) >> null
        }

        when:
        ContainerTerminalDrive.run(assemblyRunningLoop(executor, io), support, order(), CheckpointMechanicsFixtures.context(),
                gated, LawBinding.atRevision(cloneDir, GitObjects.HEAD), null)

        then:
        def stop = thrown(RunParkedException)
        stop.checkpoint()
        1 * support.recordPark({ isGatePark(it) }, TrackerWrite.NONE)

        then: 'constructive before destructive: the box is kept only after the record'
        1 * support.keepStopped()
        0 * support.completeAndDispose(_)
        0 * support.finishCleanup()

        and:
        executor.requests.isEmpty()
        persistence.entries.isEmpty()
        io.printed.join('').contains(pausedRender())
    }
}
