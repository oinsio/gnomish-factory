package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.TaskListRow
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of add-git-workflow (task 5.4): enumerates local + remote-tracking {@code gnomish/*}
 * branches, deduplicated per task with the local tip preferred, into a compact
 * {@link TaskListRow} summary — the source for {@code gnomish status}'s list mode.
 */
class TaskBranchListerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def lister = new TaskBranchLister(runner)
    Path cloneDir
    Path worktreesRoot

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private void createLocalTask(String taskId, String stage = 'implement', int attemptsUsed = 0) {
        new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE).createTask(new TaskContext(taskId, 'T', 'B', []), null, TaskState.atStageStart('implement'))
        def worktree = worktreesRoot.resolve('clone').resolve(taskId)
        def state = new TaskState(new Position.AtStage(stage), attemptsUsed, [], ExecutorUsage.none())
        def trace = new ToolTrace(new AttemptKey(taskId, stage, 0), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
        new GitAttemptPersistence(runner, worktree, taskId, ClaimEpochSource.NONE).persist(taskId, state, trace)
    }

    /** Records one completed round on an existing task branch, so its tip classifies as in-flight. */
    private void recordRound(String taskId, String stage = 'implement') {
        def worktree = worktreesRoot.resolve('clone').resolve(taskId)
        def state = TaskState.atStageStart(stage).recordQualityFailure(new AttemptRecord(
                        0, AttemptRecord.Result.QUALITY_FAILURE, Instant.EPOCH, [],
                        ExecutorUsage.none(), JudgeUsage.none(), []))
        new GitAttemptPersistence(runner, worktree, taskId, ClaimEpochSource.NONE).persist(taskId, state,
                new ToolTrace(new AttemptKey(taskId, stage, 0), []))
    }

    def "FR13: overview of all tasks — three local gnomish branches are all listed with stage and attempts"() {
        given:
        createLocalTask('PROJ-1', 'implement', 0)
        createLocalTask('PROJ-2', 'verify', 1)
        createLocalTask('PROJ-3', 'implement', 2)

        when:
        def rows = lister.list(cloneDir)

        then:
        rows.size() == 3
        rows*.taskId() as Set == ['PROJ-1', 'PROJ-2', 'PROJ-3'] as Set
        rows.find { it.taskId() == 'PROJ-2' }.stage() == 'verify'
        rows.find { it.taskId() == 'PROJ-2' }.attemptsUsed() == 1
        rows.every { it.outcome() == null }
    }

    def "FR13: remote-only tasks are listed once, and a task present both locally and on origin reads from its local tip"() {
        given: 'a bare origin, a seed clone that pushes two task branches, and an observer clone with only one local'
        def bare = initBareRepo(tempDir, 'origin.git')
        def seedClone = initWorkingRepo(tempDir, 'seed-clone')
        new File(seedClone.toFile(), 'a.txt').text = 'first'
        runner.run(seedClone, 'add', 'a.txt')
        runner.run(seedClone, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        runner.run(seedClone, 'remote', 'add', 'origin', bare.toString())
        runner.run(seedClone, 'push', 'origin', 'HEAD:refs/heads/main')

        def seedWorktrees = tempDir.resolve('seed-worktrees')
        // Task REMOTE-ONLY: created and pushed from the seed clone, never checked out locally by the observer.
        new GitTaskRepository(runner, seedClone, seedWorktrees, ClaimEpochSource.NONE).createTask(new TaskContext('REMOTE-ONLY', 'T', 'B', []), null, TaskState.atStageStart('implement'))
        new GitAttemptPersistence(runner, seedWorktrees.resolve('seed-clone').resolve('REMOTE-ONLY'), 'REMOTE-ONLY', ClaimEpochSource.NONE)
                .persist('REMOTE-ONLY', TaskState.atStageStart('implement'),
                new ToolTrace(new AttemptKey('REMOTE-ONLY', 'implement', 0), [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ]))
        runner.run(seedWorktrees.resolve('seed-clone').resolve('REMOTE-ONLY'), 'push', 'origin', 'gnomish/REMOTE-ONLY')

        // Task BOTH: pushed from the seed clone too, so origin carries it.
        new GitTaskRepository(runner, seedClone, seedWorktrees, ClaimEpochSource.NONE).createTask(new TaskContext('BOTH', 'T', 'B', []), null, TaskState.atStageStart('implement'))
        new GitAttemptPersistence(runner, seedWorktrees.resolve('seed-clone').resolve('BOTH'), 'BOTH', ClaimEpochSource.NONE)
                .persist('BOTH', TaskState.atStageStart('implement'),
                new ToolTrace(new AttemptKey('BOTH', 'implement', 0), [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ]))
        runner.run(seedWorktrees.resolve('seed-clone').resolve('BOTH'), 'push', 'origin', 'gnomish/BOTH')

        def observerClone = tempDir.resolve('observer-clone')
        def cloneResult = runner.run(
                tempDir, 'clone', '--branch', 'main', '--single-branch', bare.toString(), observerClone.toString())
        assert cloneResult.exitCode() == 0: "clone failed: ${cloneResult.stderr()}"
        // Fetch REMOTE-ONLY and BOTH as remote-tracking refs (no local branch), matching a peer instance's clone state.
        runner.run(observerClone, 'fetch', 'origin', 'gnomish/REMOTE-ONLY:refs/remotes/origin/gnomish/REMOTE-ONLY')
        runner.run(observerClone, 'fetch', 'origin', 'gnomish/BOTH:refs/remotes/origin/gnomish/BOTH')

        def observerWorktrees = tempDir.resolve('observer-worktrees')
        // BOTH also gets a local branch in the observer clone, at a further-along stage than origin.
        new GitTaskRepository(runner, observerClone, observerWorktrees, ClaimEpochSource.NONE).createTask(new TaskContext('BOTH', 'T', 'B', []), null, TaskState.atStageStart('implement'))
        def state = new TaskState(new Position.AtStage('verify'), 0, [], ExecutorUsage.none())
        new GitAttemptPersistence(runner, observerWorktrees.resolve('observer-clone').resolve('BOTH'), 'BOTH', ClaimEpochSource.NONE)
                .persist('BOTH', state,
                new ToolTrace(new AttemptKey('BOTH', 'verify', 0), [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:05:00Z'), Duration.ofMillis(50))
                ]))

        def observerLister = new TaskBranchLister(runner)

        when:
        def rows = observerLister.list(observerClone)

        then: 'each task is listed exactly once'
        rows.size() == 2
        rows*.taskId() as Set == ['REMOTE-ONLY', 'BOTH'] as Set

        and: 'BOTH is read from its local tip (stage verify), not the remote-tracking tip (stage implement)'
        rows.find { it.taskId() == 'BOTH' }.stage() == 'verify'

        and: 'REMOTE-ONLY is read from the remote-tracking ref, the only place it exists'
        rows.find { it.taskId() == 'REMOTE-ONLY' }.stage() == 'implement'
    }

    def "FR13: no gnomish/* branches anywhere returns an empty list without crashing"() {
        when:
        def rows = lister.list(cloneDir)

        then:
        noExceptionThrown()
        rows.isEmpty()
    }

    // PIT BooleanTrueReturnValsMutator on listRefs' filter lambda: a real `for-each-ref
    // refs/heads/gnomish/*` never emits a blank line or a ref outside the prefix, so this is
    // proven against a thin fake-git wrapper script (this codebase has no Mockito and
    // GitProcessRunner is final, so GitProcessRunner's own gitBinary constructor seam — the same
    // one GitProcessRunnerSpec's "nonexistent binary" scenario relies on — substitutes a script
    // that injects a blank line and an out-of-prefix ref ahead of the real one for `for-each-ref`
    // only, delegating every other subcommand to the real git binary unchanged).
    def "FR13: blank lines and out-of-prefix refs from for-each-ref are filtered out"() {
        given:
        createLocalTask('PROJ-1')
        def realRef = 'refs/heads/gnomish/PROJ-1'
        def fakeGit = tempDir.resolve('fake-git.sh')
        Files.writeString(fakeGit, """#!/bin/sh
if [ "\$1" = "for-each-ref" ]; then
  printf '\\nrefs/heads/not-gnomish/other\\n${realRef}\\n'
  exit 0
fi
exec git "\$@"
""")
        fakeGit.toFile().setExecutable(true)
        def fakeRunner = new GitProcessRunner(fakeGit.toString())
        def fakeLister = new TaskBranchLister(fakeRunner)

        when:
        def rows = fakeLister.list(cloneDir)

        then: 'only the real gnomish ref was read back — the blank line and unrelated ref never reached readRow'
        rows.size() == 1
        rows[0].taskId() == 'PROJ-1'
    }

    def "FR16: a mixed-shape clone lists one row per branch, each carrying its shape"() {
        given: 'a delivered branch, a freshly created one, an in-flight one and a parked one'
        def repository = new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
        createLocalTask('DELIVERED-1')
        repository.recordOutcome('DELIVERED-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('DELIVERED-1')
        repository.createTask(new TaskContext('FRESH-1', 'T', 'B', []), null, TaskState.atStageStart('implement'))
        createLocalTask('FLIGHT-1')
        recordRound('FLIGHT-1')
        createLocalTask('PARKED-1')
        repository.recordOutcome('PARKED-1', new TaskOutcome.Paused(TaskState.atStageStart('verify'), 'implement'))

        when:
        def rows = lister.list(cloneDir)

        then: 'exactly four rows, each rendering its own shape without an error'
        noExceptionThrown()
        rows.size() == 4
        def byId = rows.collectEntries { [(it.taskId()): it] }
        byId['DELIVERED-1'].shape() instanceof BranchShape.Delivered
        byId['FRESH-1'].shape() instanceof BranchShape.Created
        byId['FLIGHT-1'].shape() instanceof BranchShape.InProgress
        byId['PARKED-1'].shape() instanceof BranchShape.Parked

        and: 'the in-flight row still carries its content facts'
        byId['FLIGHT-1'].attemptsUsed() == 1
        byId['PARKED-1'].outcome() == 'paused'
    }

    def "FR16, UX4: one unparseable branch degrades to its own diagnostic row and never kills the listing"() {
        given: 'two healthy tasks and one whose state.json was corrupted at the tip'
        createLocalTask('HEALTHY-1')
        recordRound('HEALTHY-1')
        createLocalTask('HEALTHY-2')
        recordRound('HEALTHY-2')
        createLocalTask('BROKEN-1')
        def worktree = worktreesRoot.resolve('clone').resolve('BROKEN-1')
        new File(worktree.toFile(), '.gnomish-task/state.json').text = '{ not json at all'
        runner.run(worktree, 'add', '-A')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'break state')

        when:
        def rows = lister.list(cloneDir)

        then: 'the healthy tasks render normally'
        noExceptionThrown()
        rows.size() == 3
        rows.find {
            it.taskId() == 'HEALTHY-1'
        }.shape() instanceof BranchShape.InProgress
        rows.find {
            it.taskId() == 'HEALTHY-2'
        }.shape() instanceof BranchShape.InProgress

        and: 'the bad branch is one row naming its shape, identified by its branch name'
        def broken = rows.find { it.shape() instanceof BranchShape.Corrupt }
        broken.taskId() == 'BROKEN-1'
        (broken.shape() as BranchShape.Corrupt).reason().startsWith('state.json')
        broken.stage() == null
    }

    // FR3, FR16: a pre-contract tip carries identity without state, so there is nothing to read a
    // stage or an authoritative taskId from — the row is the shape, labelled by the branch name.
    def "FR16: a pre-contract branch (task.json without state.json) is one row carrying its shape"() {
        given:
        new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
                .createTask(new TaskContext('PRE-1', 'T', 'B', []), null, TaskState.atStageStart('implement'))
        def worktree = worktreesRoot.resolve('clone').resolve('PRE-1')
        runner.run(worktree, 'rm', '-f', '.gnomish-task/state.json')
        runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'drop state')

        when:
        def rows = lister.list(cloneDir)

        then:
        noExceptionThrown()
        rows.size() == 1
        rows[0].shape() instanceof BranchShape.Created
        rows[0].taskId() == 'PRE-1'
        rows[0].stage() == null
        rows[0].attemptsUsed() == 0
        rows[0].outcome() == null
    }
}
