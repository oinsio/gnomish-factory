package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.BareGitRepoFixture
import com.github.oinsio.gnomish.adapter.git.GitAttemptPersistence
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.springframework.boot.DefaultApplicationArguments
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, FR14, M3 of add-git-workflow (task 6.4): {@code status}/{@code usage} leave the clone
 * unchanged except the explicit narrow fetch. Builds a task branch on a separate bare "origin"
 * only — never fetched into the clone up front (mirrors {@code TaskBranchLocatorSpec}'s
 * single-branch clone setup) — so invoking {@link StatusCommand}/{@link UsageCommand} genuinely
 * exercises the narrow-fetch code path in {@code TaskBranchLocator}, not a vacuous no-op.
 *
 * <p>Before/after snapshot covers: current branch, HEAD sha, working-copy porcelain status, the
 * local branch list, and the worktree list. The only permitted mutation is the appearance of the
 * {@code refs/remotes/origin/gnomish/<task>} tracking ref.
 */
class StatusUsageReadOnlySpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path bareOrigin
    Path clone
    Path worktreesRoot

    def setup() {
        bareOrigin = initBareRepo(tempDir, 'origin.git')
        def seed = initWorkingRepo(tempDir, 'seed')
        commit(seed, 'seed.txt', 'seed content')
        runner.run(seed, 'remote', 'add', 'origin', bareOrigin.toString())
        runner.run(seed, 'push', 'origin', 'HEAD:refs/heads/main')

        worktreesRoot = tempDir.resolve('worktrees')
        def taskWorktrees = tempDir.resolve('task-build').resolve('worktrees')
        buildTaskBranch(seed, taskWorktrees, 'PROJ-1')
        runner.run(seed, 'push', 'origin', 'gnomish/PROJ-1')

        clone = tempDir.resolve('clone')
        def result = runner.run(tempDir, 'clone', '--branch', 'main', '--single-branch',
                bareOrigin.toString(), clone.toString())
        assert result.exitCode() == 0: "clone failed: ${result.stderr()}"
    }

    private void commit(Path repo, String fileName, String content) {
        new File(repo.toFile(), fileName).text = content
        runner.run(repo, 'add', fileName)
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', fileName)
    }

    /** Builds {@code gnomish/PROJ-1} with one round, in a throwaway worktree root of its own. */
    private void buildTaskBranch(Path repo, Path taskWorktrees, String taskId) {
        new GitTaskRepository(runner, repo, taskWorktrees).createTask(
                new TaskContext(taskId, 'Fix the thing', 'Body', []), null)
        def worktree = taskWorktrees.resolve(repo.fileName.toString()).resolve(taskId)
        def trace = new ToolTrace(new AttemptKey(taskId, 'implement', 0), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
        def passed = new AttemptRecord(
                0, AttemptRecord.Result.PASSED, Instant.parse('2026-07-18T09:00:00Z'),
                [] as List<CheckResult>,
                new ExecutorUsage(Duration.ofMillis(500), [], ['claude-x': new TokenUsage(100, 10, 0, 0)]),
                JudgeUsage.none())
        def state = TaskState.atStageStart('implement').recordUnburnedRound(passed)
        new GitAttemptPersistence(runner, worktree, taskId).persist(taskId, state, trace)
    }

    private Map snapshot() {
        [
            branch    : runner.run(clone, 'symbolic-ref', '--short', 'HEAD').stdout().trim(),
            head      : runner.run(clone, 'rev-parse', 'HEAD').stdout().trim(),
            porcelain : runner.run(clone, 'status', '--porcelain').stdout(),
            localRefs : runner.run(clone, 'for-each-ref', 'refs/heads/').stdout(),
            worktrees : runner.run(clone, 'worktree', 'list', '--porcelain').stdout(),
        ]
    }

    private boolean trackingRefExists() {
        runner.run(clone, 'rev-parse', '--verify', '--quiet', 'refs/remotes/origin/gnomish/PROJ-1').exitCode() == 0
    }

    private static String captureStdout(Closure action) {
        def originalOut = System.out
        def out = new ByteArrayOutputStream()
        System.out = new PrintStream(out, true, 'UTF-8')
        try {
            action.call()
        } finally {
            System.out = originalOut
        }
        return out.toString('UTF-8')
    }

    // FR13, M3: status against a branch not yet fetched locally exercises the narrow-fetch path
    // and mutates nothing else in the clone.
    def "M3, FR13: gnomish status on a not-yet-fetched task branch only creates the tracking ref"() {
        given:
        assert !trackingRefExists()
        def before = snapshot()
        def args = new DefaultApplicationArguments('status', '--dir=' + clone, 'PROJ-1')

        when:
        def output = captureStdout { new StatusCommand(worktreesRoot).run(args) }

        then: 'the fetch path was actually exercised, not vacuously true'
        trackingRefExists()
        output.contains('Task: PROJ-1')

        and: 'nothing else in the clone changed'
        def after = snapshot()
        after.branch == before.branch
        after.head == before.head
        after.porcelain == before.porcelain
        after.localRefs == before.localRefs
        after.worktrees == before.worktrees
    }

    // FR14, M3: same guarantee for usage.
    def "M3, FR14: gnomish usage on a not-yet-fetched task branch only creates the tracking ref"() {
        given:
        assert !trackingRefExists()
        def before = snapshot()
        def args = new DefaultApplicationArguments('usage', '--dir=' + clone, 'PROJ-1')

        when:
        def output = captureStdout { new UsageCommand().run(args) }

        then:
        trackingRefExists()
        output.contains('implement')

        and:
        def after = snapshot()
        after.branch == before.branch
        after.head == before.head
        after.porcelain == before.porcelain
        after.localRefs == before.localRefs
        after.worktrees == before.worktrees
    }

    // FR13, M3: once the tracking ref already exists, status must not touch the clone at all —
    // not even a redundant fetch. FETCH_HEAD's mtime is the fetch fingerprint: unchanged means
    // git never ran a network operation the second time.
    def "M3, FR13: gnomish status on an already-fetched task branch performs zero mutation, not even a redundant fetch"() {
        given:
        runner.run(clone, 'fetch', 'origin', 'gnomish/PROJ-1:refs/remotes/origin/gnomish/PROJ-1')
        assert trackingRefExists()
        def trackingShaBefore = runner.run(clone, 'rev-parse', 'refs/remotes/origin/gnomish/PROJ-1').stdout().trim()
        def fetchHeadFile = clone.resolve('.git').resolve('FETCH_HEAD').toFile()
        def fetchHeadBefore = fetchHeadFile.exists() ? fetchHeadFile.lastModified() : -1L
        Thread.sleep(20)
        def before = snapshot()
        def args = new DefaultApplicationArguments('status', '--dir=' + clone, 'PROJ-1')

        when:
        captureStdout { new StatusCommand(worktreesRoot).run(args) }

        then: 'no new fetch happened: FETCH_HEAD is untouched and the tracking ref sha is unchanged'
        (fetchHeadFile.exists() ? fetchHeadFile.lastModified() : -1L) == fetchHeadBefore
        runner.run(clone, 'rev-parse', 'refs/remotes/origin/gnomish/PROJ-1').stdout().trim() == trackingShaBefore

        and:
        def after = snapshot()
        after == before
    }
}
