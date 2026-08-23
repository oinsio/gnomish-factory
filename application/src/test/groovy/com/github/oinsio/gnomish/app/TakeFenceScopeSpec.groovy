package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * NG6, FR4 of fix-lifecycle-push: WHICH terminal outcomes the pre-park delivery fence guards. Only
 * a marker-bearing park (Escalated/Paused) is fenced; the two outcomes NG6 names — {@code
 * Completed} and {@code Aborted} — carry no pending tracker write to protect and leave no park for
 * another instance to reconcile from, so they stay purely best-effort and never spend the fence's
 * extra refs read.
 *
 * <p>Sibling of {@code TakeResumeReplicationSpec}, which owns what the fence DOES once it runs.
 */
class TakeFenceScopeSpec extends Specification implements RunChainFakes {

    @TempDir
    Path tempDir

    Path worktreesRoot
    Path worktree

    /** The round journal the store hands the run; assignable, since setup() stubs the port once. */
    AttemptPersistence journal = new InMemoryAttemptPersistence()

    Tracker tracker = Mock(Tracker)
    TaskLifecycleStore lifecycleStore = Mock(TaskLifecycleStore)
    TaskBranchGit branches = Mock(TaskBranchGit)
    TaskWorktreeGit worktrees = Stub(TaskWorktreeGit)
    TaskStoreGit store = Stub(TaskStoreGit)

    def setup() {
        worktreesRoot = tempDir.resolve('worktrees')
        worktree = worktreesRoot.resolve('PROJ-1')
        Files.createDirectories(worktree)
        branches.locate(_, _) >> new BranchLocation.Local('refs/heads/gnomish/PROJ-1')
        worktrees.ensureWorktree(_, _, _, _) >> worktree
        worktrees.salvage(_) >> Stub(WorktreeSalvager)
        store.taskRepository(_, _) >> lifecycleStore
        store.attemptPersistence(_, _) >> { journal }
        store.readRecordedState(_) >> TaskState.atStageStart('build')
        store.readTaskRecord(_) >> recordWith(null, null, false)
        tracker.fetchTask(_) >> heldByUs()
    }

    /** The real host resume chain over the ports above. */
    private TakeDispositionResume chain() {
        def git = new TaskGit(store, branches, worktrees)
        def runner = new TakeResumeRunner(assemblyRunning(new ScriptedExecutor([completedRound()])), git,
        worktreesRoot, 'taskId', new AbortHandler(tracker, FIXED_CLOCK), 3, [], new ClaimLossFlag())
        def mechanics = new HostResumeMechanics(runner, git, worktreesRoot, completingPipeline())
        new TakeDispositionResume(mechanics, new TakeDecisionResume(mechanics), git)
    }

    def "a #outcome run runs no delivery fence"() {
        given: 'a round journal that decides whether the run delivers or aborts'
        journal = roundJournal

        when:
        def result = chain().resumeExisting(CLONE_DIR, RunArguments.InteractiveMode.NONE, false,
                'PROJ-1', tracker, REF, INSTANCE)

        then:
        0 * branches.fenceParkDelivery(_, _)
        expected.isInstance(result)

        where:
        outcome | roundJournal | expected
        'delivered' | new InMemoryAttemptPersistence() | TakeResult.Delivered
        'aborted' | unwritableJournal() | TakeResult.Aborted
    }

    /**
     * A round journal that cannot be written — the engine turns the persist failure into {@code
     * Aborted}, the one non-park outcome no scripted executor can produce (an executor that throws
     * escalates as {@code CannotExecute}, which IS a fenced park).
     */
    private static AttemptPersistence unwritableJournal() {
        [
            persist: { String taskId, TaskState state, ToolTrace trace ->
                throw new IllegalStateException('journal unwritable')
            }
        ] as AttemptPersistence
    }
}
