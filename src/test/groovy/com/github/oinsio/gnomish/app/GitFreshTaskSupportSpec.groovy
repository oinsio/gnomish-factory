package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.adapter.git.state.TaskStateJson
import com.github.oinsio.gnomish.domain.engine.ExecutorUsage
import com.github.oinsio.gnomish.domain.engine.Position
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR7 of add-git-workflow: {@link GitFreshTaskSupport}'s post-completion state readback.
 *
 * {@code readFinalState} is exercised end-to-end through {@link GitModeRunnerSpec}'s "removes the
 * worktree once the task completes" scenario, but that path only ever inspects whether the
 * worktree was removed — {@code TaskOutcome.Completed#finalState} itself is write-only from there
 * on (dropped by {@code TaskOutcomeDto.Completed}, and {@code TaskWorktreeCleanup} switches only
 * on outcome type). This spec asserts directly on the value {@code readFinalState} returns, so a
 * corrupted or null readback is caught even though nothing downstream currently re-reads it.
 */
class GitFreshTaskSupportSpec extends Specification {

    @TempDir
    Path tempDir

    def "readFinalState() reads back the exact state.json content committed by the engine"() {
        given: 'a worktree whose .gnomish-task/state.json records a specific, non-default state'
        Path worktree = tempDir.resolve('worktree')
        Path gnomishTask = Files.createDirectories(worktree.resolve('.gnomish-task'))
        TaskState written = new TaskState(new Position.AtStage('review'), 2, [], ExecutorUsage.none())
        String json = TaskStateJson.mapper().writeValueAsString(StateJsonMapper.toDto(written))
        Files.writeString(gnomishTask.resolve('state.json'), json)

        when:
        TaskState read = GitFreshTaskSupport.readFinalState(worktree)

        then: 'the readback is not null and carries the exact stage/attempt count that was written'
        read != null
        read.position() == written.position()
        read.attemptsUsed() == 2
    }

    def "readFinalState() throws when state.json is missing"() {
        given:
        Path worktree = tempDir.resolve('empty-worktree')
        Files.createDirectories(worktree.resolve('.gnomish-task'))

        when:
        GitFreshTaskSupport.readFinalState(worktree)

        then:
        thrown(UncheckedIOException)
    }
}
