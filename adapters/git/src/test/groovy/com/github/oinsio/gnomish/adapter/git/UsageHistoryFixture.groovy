package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/**
 * Shared fixture for {@code UsageHistoryWalker} specs (FR14, NFR-C1 of add-git-workflow): the
 * seeded-clone setup and round builder come from {@link SeededCloneFixture} (test-fixtures,
 * shared with the application layer's usage/status specs); this trait adds the round-persisting
 * helper the walker specs need, factored out so the walker's core-behavior and edge-case
 * scenarios can live in separate spec files under the 200-line file-size cap
 * (.claude/rules/process-invariants.md) without duplicating this setup.
 */
trait UsageHistoryFixture implements SeededCloneFixture {

    UsageHistoryWalker walker

    void setupUsageHistoryFixture() {
        setupSeededClone()
        walker = new UsageHistoryWalker(runner)
    }

    GitTaskRepository taskRepository() {
        new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
    }

    Path worktreeFor(String taskId) {
        worktreesRoot.resolve('clone').resolve(taskId)
    }

    GitAttemptPersistence persistenceFor(String taskId) {
        new GitAttemptPersistence(runner, worktreeFor(taskId), taskId, ClaimEpochSource.NONE)
    }

    void persistRound(String taskId, TaskState state, String stage, int round) {
        def trace = new ToolTrace(new AttemptKey(taskId, stage, round), [
            new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
        ])
        persistenceFor(taskId).persist(taskId, state, trace)
    }
}
