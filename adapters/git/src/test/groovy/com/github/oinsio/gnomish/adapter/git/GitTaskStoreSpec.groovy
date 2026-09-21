package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchTipUnavailableException
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.branch.EnvelopePaths
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12b of split-into-modules: {@code GitTaskStore} is the store-side delegation facade the
 * application layer binds as {@code TaskStoreGit} — it hands out the per-run
 * {@code TaskLifecycleStore} / {@code AttemptPersistence} bound to one clone or worktree, reads the
 * recorded state files back, and walks a task's usage history.
 *
 * <p>Added by task 8.1 of split-into-modules for the same reason as {@link GitTaskBranchesSpec}:
 * per-module mutation scoping needs a module's classes covered by that module's own specs.
 */
class GitTaskStoreSpec extends Specification implements BareGitRepoFixture, TaskSeedFixture, StallingReadGitFixture {

    @TempDir
    Path tempDir

    GitProcessRunner runner = new GitProcessRunner()
    def store = new GitTaskStore(runner, ClaimEpochSource.NONE)
    Path cloneDir
    Path worktreesRoot

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'first')
        commitAll(cloneDir, 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    def "taskRepository hands out a lifecycle store bound to this clone"() {
        when:
        TaskLifecycleStore repository = store.taskRepository(cloneDir, worktreesRoot)

        then: 'it is bound, not merely non-null: creating a task through it lands on this clone'
        repository.createTask(new TaskContext('PROJ-1', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        runner.run(cloneDir, 'rev-parse', '--verify', '--quiet', 'refs/heads/gnomish/PROJ-1').exitCode() == 0
    }

    def "FR1 of fix-lifecycle-push: the lifecycle store it hands out pushes each commit best-effort"() {
        given: 'the clone has an origin, so a lifecycle push is observable on the remote'
        def origin = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', origin.toString())

        when:
        store.taskRepository(cloneDir, worktreesRoot).createTask(new TaskContext('PROJ-9', UntrustedText.tracker('T'), UntrustedText.tracker('B'), []), TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))

        then:
        new RemoteBranchTip(runner).read(cloneDir, 'gnomish/PROJ-9')
                == Optional.of(gitOutput(worktreeFor('PROJ-9'), 'rev-parse', 'HEAD'))
    }

    def "attemptPersistence hands out persistence bound to this worktree and task"() {
        given:
        seedTask('PROJ-2', 'Fix it')
        def worktree = worktreeFor('PROJ-2')

        when:
        AttemptPersistence persistence = store.attemptPersistence(worktree, 'PROJ-2')
        persistence.persist('PROJ-2', TaskState.atStageStart('verify'), new ToolTrace(
                        new AttemptKey('PROJ-2', 'verify', 0), [
                            new ToolCall(0, 'bash', Instant.parse('2026-07-18T10:00:00Z'), Duration.ofMillis(50))
                        ]))

        then: 'the commit landed on the task branch in this worktree'
        runner.run(worktree, 'show', 'HEAD:.gnomish-task/state.json').stdout().forParsing().contains('verify')
    }

    def "FR1 of fix-envelope-medium: both envelopes are read at the branch tip the worktree has checked out"() {
        given:
        def state = seedTask('PROJ-3', 'Fix it')

        when:
        def read = store.readRecordedState(worktreeFor('PROJ-3'))
        def record = store.readTaskRecord(worktreeFor('PROJ-3'))

        then:
        read == Optional.of(state)
        record.get().context().taskId() == 'PROJ-3'
        record.get().context().title().forLog() == 'Fix it'
    }

    def "FR1 of fix-envelope-medium: an envelope the tip does not carry reads as empty, not as a fault"() {
        given: 'a worktree on a branch whose tip never carried a task envelope'
        def worktree = addWorktree(cloneDir, tempDir.resolve('plain'), 'plain')

        expect: 'absence is a value — the routing decision it feeds belongs to the caller, not to a cause chain'
        store.readRecordedState(worktree) == Optional.empty()
        store.readTaskRecord(worktree) == Optional.empty()
    }

    def "FR1 of fix-envelope-medium: a worktree file that differs from the tip does not move the answer"() {
        given:
        def state = seedTask('PROJ-8', 'Fix it')
        def worktree = worktreeFor('PROJ-8')

        and: 'an uncommitted edit on disk that names another stage'
        def onDisk = worktree.resolve('.gnomish-task/state.json')
        Files.writeString(onDisk, Files.readString(onDisk).replace('implement', 'verify'))
        assert Files.readString(onDisk).contains('verify')

        expect: 'the tip wins: the read answers what the branch records, not what the working copy holds'
        store.readRecordedState(worktree) == Optional.of(state)
    }

    def "readTaskRecord answers the tip when a killed predecessor removed the envelope from the worktree"() {
        given: 'FR1 of fix-envelope-medium: a completed task whose tip carries the Completed envelope'
        def state = seedTask('PROJ-6', 'Fix it')
        def worktree = worktreeFor('PROJ-6')
        store.taskRepository(cloneDir, worktreesRoot).recordOutcome('PROJ-6', new TaskOutcome.Completed(state))

        and: 'a cleanup killed between its staged removal and its commit: the worktree lost the envelope, the tip kept it'
        assert runner.run(worktree, 'rm', '-r', EnvelopePaths.DIR_NAME).exitCode() == 0
        assert runner.run(worktree, 'show', 'HEAD:.gnomish-task/task.json').exitCode() == 0

        when:
        def record = store.readTaskRecord(worktree)

        then: 'the read resolves at HEAD, so it answers what the branch records — not what the disk happens to hold'
        record.get().context().taskId() == 'PROJ-6'
        record.get().outcome() instanceof RecordedOutcome.Completed
    }

    def "NFR-R2 of fix-envelope-medium: a read cut off before git exits is unavailability, never absence"() {
        given: 'a stand-in git that stalls on every read, so the read can only end on an interrupt'
        Path stallDir = Files.createDirectories(tempDir.resolve('stall'))
        def stalled = new GitTaskStore(new GitProcessRunner(stallingGit(stallDir).toString()), ClaimEpochSource.NONE)

        when:
        Throwable thrown = null
        def reader = new Thread({
            try {
                stalled.readTaskRecord(stallDir)
            } catch (Throwable t) {
                thrown = t
            }
        })
        reader.start()
        awaitReadStarted(stallDir)
        reader.interrupt()
        reader.join(Duration.ofSeconds(30).toMillis())

        then: 'an interrupted read established nothing about the tip, so it must not answer "absent"'
        thrown instanceof BranchTipUnavailableException
    }

    def "NFR-P1 of fix-envelope-medium: each read is one git invocation and reads no file from the worktree"() {
        given:
        seedTask('PROJ-7', 'Fix it')
        def worktree = worktreeFor('PROJ-7')
        Path log = tempDir.resolve('argv.log')
        def counting = new GitTaskStore(new GitProcessRunner(recordingGit(log).toString()), ClaimEpochSource.NONE)

        and: 'nothing of the envelope is left on disk, so no filesystem read could answer either call'
        assert runner.run(worktree, 'rm', '-r', EnvelopePaths.DIR_NAME).exitCode() == 0

        when:
        def record = counting.readTaskRecord(worktree)

        then:
        record.get().context().taskId() == 'PROJ-7'
        recordedSubcommands(log) == ['show']

        when:
        def state = counting.readRecordedState(worktree)

        then:
        state.isPresent()
        recordedSubcommands(log) == ['show', 'show']
    }

    def "usageHistory delegates to UsageHistoryWalker and returns the walked history"() {
        given:
        seedTask('PROJ-5', 'Fix it')

        when:
        def history = store.usageHistory(cloneDir, 'PROJ-5')

        then: 'the walk reached the task branch — a state with no recorded attempts yields a Found with no rows'
        history instanceof UsageHistoryResult.Found
        (history as UsageHistoryResult.Found).totals() != null
    }

    def "usageHistory reports a task with no branch as not-found rather than an empty history"() {
        expect:
        store.usageHistory(cloneDir, 'NO-SUCH') instanceof UsageHistoryResult.NotFound
    }
}
