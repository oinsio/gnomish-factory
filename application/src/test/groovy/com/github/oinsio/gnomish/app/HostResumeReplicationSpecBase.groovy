package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.TrackerWrite
import com.github.oinsio.gnomish.app.port.git.*
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.fake.VirtualTimeRetries
import java.nio.file.Files
import java.nio.file.Path
import java.util.function.UnaryOperator
import spock.lang.Specification
import spock.lang.TempDir
/**
 * The host resume chain over port fakes that the replication specs drive: a lifecycle store, branch
 * and worktree ports, a tracker, and the real routing chain built over them. FR3, FR4, FR5 of
 * fix-lifecycle-push. Shared by {@link TakeResumeReplicationSpec} (where a resumed run touches the
 * remote) and {@link TakeParkFenceExhaustionSpec} (what a park reports when the fence could not
 * deliver).
 */
abstract class HostResumeReplicationSpecBase extends Specification implements RunChainFakes {


    static final String BEHIND_NOTE =
    'Note: origin is behind this park — branch gnomish/PROJ-1 could not be pushed.'

    @TempDir
    Path tempDir

    RegisteredClone registeredClone
    Path worktree

    Tracker tracker = Mock(Tracker)
    TaskLifecycleStore lifecycleStore = Mock(TaskLifecycleStore)
    TaskBranchGit branches = Mock(TaskBranchGit)
    TaskWorktreeGit worktrees = Stub(TaskWorktreeGit)
    TaskStoreGit store = Stub(TaskStoreGit)

    /**
     * FR12, D13 of add-base-ref-resolution: resume always resolves its pinned base ref now, so the
     * port-fake chain needs a working {@link BaseRefGit} rather than {@link BaseRefGit#UNWIRED} —
     * {@link RunChainFakes#resumingBaseRefGit} echoes the resolved tip back, which is exactly
     * today's placeholder SHA input. A field, not a per-call helper: Spock's ordered {@code then:}
     * verification tracks every mock/stub invocation, and building the fake lazily inside the
     * routing chain (i.e. during {@code when:}) misfiles its background interaction into the
     * ordered sequence.
     */
    BaseRefGit baseRefGit = resumingBaseRefGit()

    def setup() {
        registeredClone = RegisteredCloneFixture.unregistered(tempDir.resolve('home'), CLONE_DIR)
        worktree = tempDir.resolve('worktrees').resolve('PROJ-1')
        Files.createDirectories(worktree)
        branches.locate(_, _) >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        branches.adopt(_, _, _) >> { Path clone, String taskId, BranchLocation location ->
            new BranchAdoption.AlreadyLocal(taskId)
        }
        branches.classifyShape(_, _) >> new BranchShape.InProgress()
        worktrees.ensureWorktree(_, _) >> worktree
        worktrees.salvage(_) >> Stub(WorktreeSalvager)
        store.taskRepository(_) >> lifecycleStore
        store.attemptPersistence(_, _) >> new InMemoryAttemptPersistence()
        store.readRecordedState(_, _) >> Optional.of(TaskState.atStageStart('build'))
        tracker.fetchTask(_) >> heldByUs()
    }

    TaskGit git() {
        new TaskGit(store, branches, worktrees, UnaryOperator.identity(), baseRefGit, new ClaimEpochBook())
    }

    /** The real routing chain over the ports above; {@code verdict} decides whether a run parks. */
    TakeDispositionResume chain(ScriptedExecutor executor, Verdict verdict = new Verdict.Pass()) {
        def git = git()
        def runner = new TakeResumeRunner(slotWiring(assemblyRunning(executor, verdict), git, tracker, registeredClone))
        def mechanics = new HostResumeMechanics(runner, git, registeredClone, completingPipeline())
        new TakeDispositionResume(mechanics, new TakeDecisionResume(mechanics), git, VirtualTimeRetries.terminalWrite())
    }

    /** A chain whose engine run escalates: two rounds, the last verification failing. */
    TakeDispositionResume parkingChain() {
        chain(new ScriptedExecutor([
            completedRound(),
            completedRound()
        ]), new Verdict.Fail([]))
    }

    TakeResult resume(TakeDispositionResume chain) {
        chain.resumeExisting(takeOrder(heldByUs(), tracker, runOrder(completingPipeline())), new BranchShape.InProgress())
    }
}
