package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.UsageHistoryResult
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.port.AttemptPersistence
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
class GitTaskStoreSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def store = new GitTaskStore(runner)
    Path cloneDir
    Path worktreesRoot

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'first')
        commitAll(cloneDir, 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private Path worktreeFor(String taskId) {
        worktreesRoot.resolve('clone').resolve(taskId)
    }

    private TaskState seedTask(String taskId) {
        new GitTaskRepository(runner, cloneDir, worktreesRoot).createTask(new TaskContext(taskId, 'Fix it', 'B', []), null)
        def state = TaskState.atStageStart('implement')
        def trace = new ToolTrace(new AttemptKey(taskId, 'implement', 0), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
        new GitAttemptPersistence(runner, worktreeFor(taskId), taskId).persist(taskId, state, trace)
        state
    }

    def "taskRepository hands out a lifecycle store bound to this clone"() {
        when:
        TaskLifecycleStore repository = store.taskRepository(cloneDir, worktreesRoot)

        then: 'it is bound, not merely non-null: creating a task through it lands on this clone'
        repository.createTask(new TaskContext('PROJ-1', 'T', 'B', []), null)
        runner.run(cloneDir, 'rev-parse', '--verify', '--quiet', 'refs/heads/gnomish/PROJ-1').exitCode() == 0
    }

    def "attemptPersistence hands out persistence bound to this worktree and task"() {
        given:
        seedTask('PROJ-2')
        def worktree = worktreeFor('PROJ-2')

        when:
        AttemptPersistence persistence = store.attemptPersistence(worktree, 'PROJ-2')
        persistence.persist('PROJ-2', TaskState.atStageStart('verify'), new ToolTrace(
                        new AttemptKey('PROJ-2', 'verify', 0), [
                            new ToolCall(0, 'bash', Instant.parse('2026-07-18T10:00:00Z'), Duration.ofMillis(50))
                        ]))

        then: 'the commit landed on the task branch in this worktree'
        runner.run(worktree, 'show', 'HEAD:.gnomish-task/state.json').stdout().contains('verify')
    }

    def "readRecordedState reads the worktree's state.json back into the domain state"() {
        given:
        def state = seedTask('PROJ-3')

        expect:
        store.readRecordedState(worktreeFor('PROJ-3')) == state
    }

    def "readTaskRecord reads the worktree's task.json back into the port record"() {
        given:
        seedTask('PROJ-4')

        when:
        def record = store.readTaskRecord(worktreeFor('PROJ-4'))

        then:
        record.context().taskId() == 'PROJ-4'
        record.context().title() == 'Fix it'
    }

    def "reading a state file that is not there fails loudly, naming the file"() {
        when:
        store.readRecordedState(tempDir.resolve('no-such-worktree'))

        then:
        def e = thrown(java.io.UncheckedIOException)
        e.message.contains('state.json')
    }

    def "usageHistory delegates to UsageHistoryWalker and returns the walked history"() {
        given:
        seedTask('PROJ-5')

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
