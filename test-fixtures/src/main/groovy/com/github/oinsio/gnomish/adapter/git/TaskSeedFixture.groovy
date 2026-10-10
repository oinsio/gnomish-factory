package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.git.TaskWorktreePath
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.project.RegisteredClone
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Shared "seed one task" step for adapters/git specs that read a task branch back through some
 * collaborator and need a real branch plus one recorded attempt to read: creates the task on
 * {@link #getCloneDir}, then persists one {@code implement} attempt against the worktree {@link
 * #worktreeFor} names for it.
 *
 * <p>Implementing classes supply {@code runner}, {@code cloneDir} and {@code registeredClone} as
 * plain Groovy properties (the {@code def field = value} shape every caller already uses) — the
 * trait only reads them. The clone is obtained through the production registry ({@code
 * RegisteredCloneFixture.registered}, FR9 of add-project-registry).
 */
trait TaskSeedFixture {

    abstract GitProcessRunner getRunner()

    abstract Path getCloneDir()

    abstract RegisteredClone getRegisteredClone()

    /** The worktree path a task's own branch is checked out into, by the production formula. */
    Path worktreeFor(String taskId) {
        TaskWorktreePath.resolve(registeredClone, taskId)
    }

    /**
     * Creates {@code taskId} at stage start on {@code cloneDir} and persists one recorded
     * {@code implement} attempt in its worktree, returning the state that was persisted.
     */
    TaskState seedTask(String taskId, String title = 'T') {
        TaskState state = TaskState.atStageStart('implement')
        new GitTaskRepository(runner, registeredClone, ClaimEpochSource.NONE, new VirtualClock()).createTask(
                new TaskContext(taskId, UntrustedText.tracker(title), UntrustedText.tracker('B'), []),
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
