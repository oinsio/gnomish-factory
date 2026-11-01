package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.git.TaskWorktreePath
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.AttemptRecord
import com.github.oinsio.gnomish.domain.engine.CheckResult
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.domain.engine.Stop
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.TokenUsage
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.AttemptRecords
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import com.github.oinsio.gnomish.domain.engine.fake.Unpriced
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Reusable Spock fixture: a working clone seeded with one commit and registered through the
 * production project registry — its {@link RegisteredClone} names the worktree folder git task
 * branches are checked out in (FR9 of add-project-registry) — and an {@link AttemptRecord} builder
 * for round fixtures. Factored out of the git and application adapters' usage/status specs, which each
 * repeated this same "seed a clone, build a round" setup (FR14, NFR-C1 of add-git-workflow).
 *
 * <p>Implementers provide {@code tempDir}, typically via Spock's {@code @TempDir}.
 */
trait SeededCloneFixture implements BareGitRepoFixture, RegisteredBranchFixture {

    /** The round tokens {@link #round} has minted so far. */
    int roundsMinted = 0

    abstract Path getTempDir()

    Path cloneDir
    RegisteredClone registeredClone
    GitProcessRunner runner = new GitProcessRunner()

    void setupSeededClone() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        registeredClone = RegisteredCloneFixture.registered(tempDir.resolve('home'), cloneDir)
    }

    /** The worktree a task's own branch is checked out into, by the production formula. */
    Path worktreeFor(String taskId) {
        TaskWorktreePath.resolve(registeredClone, taskId)
    }

    void persistRound(String taskId, TaskState state, String stage = 'implement', int round = 0,
            String title = 'Fix the thing') {
        new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock()).createTask(
                new TaskContext(taskId, UntrustedText.tracker(title), UntrustedText.tracker('Body'), []),
                TaskStart.commit(cloneDir, 'HEAD'), TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
        registerTip(cloneDir, taskId)
        def worktree = worktreeFor(taskId)
        def persistence = new GitAttemptPersistence(runner, worktree, taskId, ClaimEpochSource.NONE)
        def trace = new ToolTrace(new AttemptKey(taskId, stage, round), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
        persistence.persist(taskId, RoundStates.withRound(state, trace.key()), trace)
    }

    /**
     * A round whose executor reported {@code inputTokens}, opened on a round token of its own: each
     * call mints the next token, as each round of a branch opens on the tip its predecessor left, so
     * the usage fold counts every round it is handed (FR17 of define-executor-contract).
     */
    AttemptRecord round(int roundNumber, AttemptRecord.Result result, long wallMillis, long inputTokens) {
        this.round(AttemptRecords.TASK_ID, AttemptRecords.STAGE, roundNumber, result, wallMillis, inputTokens)
    }

    /**
     * The round {@link #round(int, AttemptRecord.Result, long, long)} builds, recorded under {@code
     * taskId} and {@code stage} — the identity a usage walk reads its row's task and stage from
     * (FR14, FR19 of define-executor-contract).
     */
    AttemptRecord round(String taskId, String stage, int round, AttemptRecord.Result result, long wallMillis,
            long inputTokens) {
        roundsMinted = roundsMinted + 1
        AttemptRecords.identified(
                taskId,
                stage,
                RoundToken.of(String.format('%040x', roundsMinted)),
                round,
                result,
                Instant.parse('2026-07-18T09:00:00Z').plusSeconds(round * 60),
                [] as List<CheckResult>,
                new ExecutorUsage(Duration.ofMillis(wallMillis), [],
                Unpriced.of(['claude-x': new TokenUsage(inputTokens, 10, 0, 0)])),
                [], Stop.none())
    }
}
