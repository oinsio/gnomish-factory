package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR6, FR8 of add-git-workflow: the "record + cleanup" pair both git-mode terminal boundaries (a
 * fresh {@code GitModeRunner} and a resumed {@code GitResumeRunner}) reach the end of a task
 * through. The pairing exists so the ORDER is stated in one place — a task must be durably
 * recorded before its worktree is judged safe to remove — which is exactly what this spec pins.
 *
 * <p>Added by task 8.7 of split-into-modules (design D13(c)): the helper is an `:application`
 * class whose only killers lived in the composition root's integration suites.
 */
class GitOutcomeRecorderSpec extends Specification {

    private static final Path CLONE_DIR = Path.of('/tmp/clone')
    private static final Path WORKTREE = Path.of('/tmp/worktrees/PROJ-1')
    private static final TaskState FINAL_STATE = TaskState.atStageStart('build')

    // FR6, FR8: both halves happen, and the durable record comes FIRST — a cleanup that ran before
    // the outcome was recorded could remove the working copy of a task whose result was then lost.
    def "records the outcome durably before disposing of the worktree"() {
        given:
        def taskRepository = Mock(TaskRepository)
        def worktrees = Mock(TaskWorktreeGit)
        def outcome = new TaskOutcome.Completed(FINAL_STATE)

        when:
        GitOutcomeRecorder.recordAndCleanUp(worktrees, taskRepository, CLONE_DIR, WORKTREE, 'PROJ-1', outcome)

        then: 'the outcome is recorded for this task first'
        1 * taskRepository.recordOutcome('PROJ-1', outcome)

        then: 'and only then is the worktree handed to the outcome-driven disposal'
        1 * worktrees.cleanUp(CLONE_DIR, WORKTREE, outcome)
        0 * _
    }

    // FR6: the disposal is outcome-DRIVEN — the recorder never decides keep-vs-remove itself, it
    // hands the terminal outcome through so the worktree port can apply its own rule.
    def "passes the terminal outcome through to both ports unchanged"() {
        given:
        def taskRepository = Mock(TaskRepository)
        def worktrees = Mock(TaskWorktreeGit)

        when:
        GitOutcomeRecorder.recordAndCleanUp(worktrees, taskRepository, CLONE_DIR, WORKTREE, 'PROJ-2', outcome)

        then:
        1 * taskRepository.recordOutcome('PROJ-2', outcome)
        1 * worktrees.cleanUp(CLONE_DIR, WORKTREE, outcome)

        where:
        outcome << [
            new TaskOutcome.Completed(FINAL_STATE),
            new TaskOutcome.Paused(FINAL_STATE, 'build')
        ]
    }
}
