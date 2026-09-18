package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
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
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Reusable Spock fixture: a working clone seeded with one commit, plus the {@code worktreesRoot}
 * git task branches are checked out under, and an {@link AttemptRecord} builder for round
 * fixtures. Factored out of the git and application adapters' usage/status specs, which each
 * repeated this same "seed a clone, build a round" setup (FR14, NFR-C1 of add-git-workflow).
 *
 * <p>Implementers provide {@code tempDir}, typically via Spock's {@code @TempDir}.
 */
trait SeededCloneFixture implements BareGitRepoFixture {

    abstract Path getTempDir()

    Path cloneDir
    Path worktreesRoot
    GitProcessRunner runner = new GitProcessRunner()

    void setupSeededClone() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    void persistRound(String taskId, TaskState state, String stage = 'implement', int round = 0,
            String title = 'Fix the thing') {
        new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE).createTask(
                new TaskContext(taskId, UntrustedText.tracker(title), UntrustedText.tracker('Body'), []),
                TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
        def worktree = worktreesRoot.resolve('clone').resolve(taskId)
        def persistence = new GitAttemptPersistence(runner, worktree, taskId, ClaimEpochSource.NONE)
        def trace = new ToolTrace(new AttemptKey(taskId, stage, round), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
        persistence.persist(taskId, state, trace)
    }

    AttemptRecord round(int round, AttemptRecord.Result result, long wallMillis, long inputTokens) {
        new AttemptRecord(
                round,
                result,
                Instant.parse('2026-07-18T09:00:00Z').plusSeconds(round * 60),
                [] as List<CheckResult>,
                new ExecutorUsage(Duration.ofMillis(wallMillis), [],
                ['claude-x': new TokenUsage(inputTokens, 10, 0, 0)]),
                JudgeUsage.none(), [])
    }
}
