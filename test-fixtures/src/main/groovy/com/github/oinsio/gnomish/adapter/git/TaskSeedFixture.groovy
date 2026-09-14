package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Shared "seed one task" step for adapters/git specs that read a task branch back through some
 * collaborator and need a real branch plus one recorded attempt to read: creates the task on
 * {@link #getCloneDir}, then persists one {@code implement} attempt against the worktree {@link
 * #worktreeFor} names for it.
 *
 * <p>Implementing classes supply {@code runner}, {@code cloneDir} and {@code worktreesRoot} as
 * plain Groovy properties (the {@code def field = value} shape every caller already uses) — the
 * trait only reads them.
 */
trait TaskSeedFixture {

    abstract GitProcessRunner getRunner()

    abstract Path getCloneDir()

    abstract Path getWorktreesRoot()

    /** The worktree path a task's own branch is checked out into, under {@code worktreesRoot}. */
    Path worktreeFor(String taskId) {
        worktreesRoot.resolve('clone').resolve(taskId)
    }

    /**
     * Creates {@code taskId} at stage start on {@code cloneDir} and persists one recorded
     * {@code implement} attempt in its worktree, returning the state that was persisted.
     */
    TaskState seedTask(String taskId, String title = 'T') {
        TaskState state = TaskState.atStageStart('implement')
        new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE).createTask(
                new TaskContext(taskId, title, 'B', []),
                TaskStart.commit(cloneDir, 'HEAD'),
                TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                state)
        def trace = new ToolTrace(new AttemptKey(taskId, 'implement', 0), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(100))
        ])
        new GitAttemptPersistence(runner, worktreeFor(taskId), taskId, ClaimEpochSource.NONE).persist(taskId, state, trace)
        state
    }
}
