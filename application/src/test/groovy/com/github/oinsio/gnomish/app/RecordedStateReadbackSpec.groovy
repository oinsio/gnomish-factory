package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.SeededCloneFixture
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR7 of add-git-workflow: {@link GitFreshTaskSupport}'s post-completion state readback.
 *
 * {@code readRecordedState} is exercised end-to-end through {@code GitModeRunnerSpec}'s "removes the
 * worktree once the task completes" scenario, but that path only ever inspects whether the
 * worktree was removed — {@code TaskOutcome.Completed#finalState} itself is write-only from there
 * on (dropped by {@code RecordedOutcome.Completed}, and {@code TaskWorktreeCleanup} switches only
 * on outcome type). This spec asserts directly on the value {@code readRecordedState} returns, so a
 * corrupted or empty readback is caught even though nothing downstream currently re-reads it.
 *
 * <p>The read resolves at the worktree's {@code HEAD} (FR1 of fix-envelope-medium), so the subject
 * is a real task branch rather than a bare directory with a file in it, and absence travels as an
 * empty {@code Optional} rather than as an I/O fault. The adapter-level matrix of that protocol —
 * a worktree file differing from the tip, a cut-off read, the invocation count — belongs to
 * {@code GitTaskStoreSpec} in {@code :adapters:git}; what this spec keeps is the application-side
 * question: does the value the run reads back equal the state the engine committed?
 */
class RecordedStateReadbackSpec extends Specification implements SeededCloneFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupSeededClone()
    }

    def "readRecordedState() reads back the exact state.json content the engine committed"() {
        given: 'a task branch whose last round committed a specific, non-default state'
        TaskState written = new TaskState(new Position.AtStage('review'), 2, [], ExecutorUsage.none())
        persistRound('PROJ-1', written)

        when:
        Optional<TaskState> read = TaskGitFixture.real().store().readRecordedState(worktreeFor('PROJ-1'))

        then: 'the readback carries the exact stage and attempt count that was committed'
        read.isPresent()
        read.get().position() == written.position()
        read.get().attemptsUsed() == 2
    }

    def "readRecordedState() answers empty when the tip carries no state envelope"() {
        given: 'a worktree on a branch whose tip never carried one'
        Path worktree = addWorktree(cloneDir, tempDir.resolve('plain'), 'plain')

        expect: 'FR1 of fix-envelope-medium: absence is a value the caller routes on, not a fault'
        TaskGitFixture.real().store().readRecordedState(worktree) == Optional.empty()
    }

    private Path worktreeFor(String taskId) {
        worktreesRoot.resolve('clone').resolve(taskId)
    }
}
