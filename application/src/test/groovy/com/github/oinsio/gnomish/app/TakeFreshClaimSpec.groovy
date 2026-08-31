package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.git.TaskWorktreePath
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.take.AbortHandler
import com.github.oinsio.gnomish.app.take.TakeResult
import com.github.oinsio.gnomish.domain.engine.Engine
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.fake.InMemoryAttemptPersistence
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR9, FR11 (design D3) of add-tracker-port: the FIRST claim of a task — the branch and worktree
 * are created, the tracker's task is synthesized into a pipeline context, and the engine runs once
 * against the new worktree. This spec drives the whole fresh path to a terminal result over port
 * fakes: real {@code TakeFreshClaim} -> real {@code TakeEngineExecution} -> a real {@link Engine}
 * over the domain's scripted engine-port fakes, with the git and tracker ports scripted at the
 * edges (design D13(c) of split-into-modules).
 *
 * <p>What that buys over the composition-root lifecycle suites: the run-start HYGIENE and the
 * terminal ORDER are asserted directly — worktrees pruned and hooks neutralized before anything is
 * created, and the outcome recorded durably and the tracker finished after the engine returns.
 *
 * <p>Added by task 8.7 of split-into-modules.
 */
class TakeFreshClaimSpec extends Specification implements RunChainFakes {

    @TempDir
    Path tempDir

    Path cloneDir
    Path worktreesRoot

    // DirectoryWorkspace refuses a path that is not an existing directory, so the worktree the
    // fresh path resolves to is materialized here — standing in for the `git worktree add` the
    // real TaskRepository would have performed.
    def setup() {
        cloneDir = tempDir.resolve('gnomish-clone')
        worktreesRoot = tempDir.resolve('worktrees')
        Files.createDirectories(cloneDir)
        Files.createDirectories(TaskWorktreePath.resolve(worktreesRoot, cloneDir, 'PROJ-1'))
        Files.createDirectories(TaskWorktreePath.resolve(worktreesRoot, cloneDir, 'PROJ-9'))
    }

    // FR9, FR11: the whole fresh path, end to end. The three things only this class sequences are
    // asserted in order — run-start hygiene BEFORE anything is created, the branch created once
    // from the synthesized tracker task, and the terminal record + tracker finish after the engine
    // returns Completed.
    def "creates the task and runs the engine once, hardening first and finishing last"() {
        given:
        def tracker = Mock(Tracker)
        def lifecycleStore = Mock(TaskLifecycleStore)
        def worktrees = Mock(TaskWorktreeGit)
        def branches = Mock(TaskBranchGit)
        def store = Stub(TaskStoreGit) {
            taskRepository(_, _) >> lifecycleStore
            attemptPersistence(_, _) >> new InMemoryAttemptPersistence()
            // Scripted, not defaulted: the port returns a record, which Spock cannot invent.
            readTaskRecord(_) >> freshRecord()
        }
        def executor = new ScriptedExecutor([completedRound()])
        def definition = completingPipeline()
        // The run's own revocation check re-reads the task each persist: still Working, held by us.
        tracker.fetchTask(_) >> heldByUs()

        when:
        def result = TakeFreshClaim.claim(
                assemblyRunning(executor), new TaskGit(store, branches, worktrees), worktreesRoot,
                new AbortHandler(tracker, FIXED_CLOCK), 3, [], cloneDir, null, definition,
                RunArguments.InteractiveMode.NONE, readyTask(), tracker, INSTANCE, new ClaimLossFlag())

        then: 'run-start hygiene runs before anything is created (FR17, design D11)'
        1 * worktrees.pruneWorktrees(cloneDir)

        then:
        1 * branches.harden(cloneDir)

        then: 'the branch is created once, then the run reaches its terminal boundary'
        1 * lifecycleStore.createTask({ it.taskId() == 'PROJ-1' }, 'HEAD', _)
        1 * lifecycleStore.recordOutcome('PROJ-1', _ as TaskOutcome.Completed)
        1 * tracker.finish(REF, _)

        then: 'FR10: the destructive tail — cleanup commit, then worktree disposal — follows the write'
        1 * lifecycleStore.finishCleanup('PROJ-1')
        1 * worktrees.cleanUp(cloneDir, _, _ as TaskOutcome.Completed)

        and: 'the engine really ran the stage, and the result is a delivery'
        executor.requests.size() == 1
        executor.requests[0].context().taskId() == 'PROJ-1'
        result instanceof TakeResult.Delivered
    }

    // FR9: the tracker's own task is what the run is built from — the synthesized context carries
    // the tracker taskId, so the branch, the worktree and the engine all name the same task.
    def "synthesizes the run's context from the tracker task"() {
        given:
        def tracker = Mock(Tracker)
        def lifecycleStore = Mock(TaskLifecycleStore)
        def store = Stub(TaskStoreGit) {
            taskRepository(_, _) >> lifecycleStore
            attemptPersistence(_, _) >> new InMemoryAttemptPersistence()
            // Scripted, not defaulted: the port returns a record, which Spock cannot invent.
            readTaskRecord(_) >> freshRecord()
        }

        and:
        tracker.fetchTask(_) >> heldByUs('PROJ-9')

        when:
        TakeFreshClaim.claim(
                assemblyRunning(new ScriptedExecutor([completedRound()])),
                new TaskGit(store, Stub(TaskBranchGit), Stub(TaskWorktreeGit)), worktreesRoot,
                new AbortHandler(tracker, FIXED_CLOCK), 3, [], cloneDir, 'release/1.2', completingPipeline(),
                RunArguments.InteractiveMode.NONE, readyTask('PROJ-9'), tracker, INSTANCE, new ClaimLossFlag())

        then: 'the explicit --base is passed through, and the context carries the tracker taskId'
        1 * lifecycleStore.createTask({
            it.taskId() == 'PROJ-9'
        }, 'release/1.2', _)
        1 * tracker.finish(REF, _)
    }
}
