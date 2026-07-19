package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.JudgeUsage
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Shared fixture for {@code UsageHistoryWalker} specs (FR14, NFR-C1 of add-git-workflow): a
 * working clone with one round-persisting helper, factored out so the walker's core-behavior and
 * edge-case scenarios can live in separate spec files under the 200-line file-size cap
 * (.claude/rules/process-invariants.md) without duplicating this setup.
 */
trait UsageHistoryFixture implements BareGitRepoFixture {

    abstract Path getTempDir()

    Path cloneDir
    Path worktreesRoot
    GitProcessRunner runner = new GitProcessRunner()
    UsageHistoryWalker walker = new UsageHistoryWalker(runner)

    void setupUsageHistoryFixture() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    GitTaskRepository taskRepository() {
        new GitTaskRepository(runner, cloneDir, worktreesRoot)
    }

    Path worktreeFor(String taskId) {
        worktreesRoot.resolve('clone').resolve(taskId)
    }

    GitAttemptPersistence persistenceFor(String taskId) {
        new GitAttemptPersistence(runner, worktreeFor(taskId), taskId)
    }

    static AttemptRecord round(int round, AttemptRecord.Result result, long wallMillis, long inputTokens) {
        new AttemptRecord(
                round,
                result,
                Instant.parse('2026-07-18T09:00:00Z').plusSeconds(round * 60),
                [] as List<CheckResult>,
                new ExecutorUsage(Duration.ofMillis(wallMillis), [],
                ['claude-x': new TokenUsage(inputTokens, 10, 0, 0)]),
                JudgeUsage.none())
    }

    void persistRound(String taskId, TaskState state, String stage, int round) {
        def trace = new ToolTrace(new AttemptKey(taskId, stage, round), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
        ])
        persistenceFor(taskId).persist(taskId, state, trace)
    }
}
