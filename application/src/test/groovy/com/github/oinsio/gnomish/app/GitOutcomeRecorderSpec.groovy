package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.port.TaskRepository
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EscalationReport
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
        def branches = Mock(TaskBranchGit)
        def git = new TaskGit(Stub(TaskStoreGit), branches, worktrees)
        def outcome = new TaskOutcome.Completed(FINAL_STATE)

        when:
        GitOutcomeRecorder.recordAndCleanUp(git, taskRepository, CLONE_DIR, WORKTREE, 'PROJ-1', outcome)

        then: 'the outcome is recorded for this task first'
        1 * taskRepository.recordOutcome('PROJ-1', outcome)

        then: 'and only then is the worktree handed to the outcome-driven disposal'
        1 * worktrees.cleanUp(CLONE_DIR, WORKTREE, outcome)

        then: 'FR3 of fix-lifecycle-push: the terminal boundary closes with the reconciliation check'
        1 * branches.reconcileRemote(CLONE_DIR, 'PROJ-1', 'terminal-boundary')
        0 * _
    }

    // FR6: the disposal is outcome-DRIVEN — the recorder never decides keep-vs-remove itself, it
    // hands the terminal outcome through so the worktree port can apply its own rule.
    // FR3, NFR-C1 of fix-lifecycle-push: the ONE decision the recorder does make is whether to
    // spend the terminal-boundary refs read. A park's caller runs the delivery fence over the same
    // unchanged tip right afterwards — same origin gate, same ls-remote, a stronger push — so
    // reconciling first would buy nothing and cost a second round-trip.
    def "passes the terminal outcome through to both ports unchanged"() {
        given:
        def taskRepository = Mock(TaskRepository)
        def worktrees = Mock(TaskWorktreeGit)
        def branches = Mock(TaskBranchGit)
        def git = new TaskGit(Stub(TaskStoreGit), branches, worktrees)

        when:
        GitOutcomeRecorder.recordAndCleanUp(git, taskRepository, CLONE_DIR, WORKTREE, 'PROJ-2', outcome)

        then:
        1 * taskRepository.recordOutcome('PROJ-2', outcome)
        1 * worktrees.cleanUp(CLONE_DIR, WORKTREE, outcome)

        and: 'FR3, NFR-C1: the reconciliation closes every NON-park boundary; a park is left to its fence'
        reconciliations * branches.reconcileRemote(CLONE_DIR, 'PROJ-2', 'terminal-boundary')

        where:
        outcome || reconciliations
        new TaskOutcome.Completed(FINAL_STATE) || 1
        new TaskOutcome.Aborted(FINAL_STATE, new AttemptKey('PROJ-2', 'build', 0), 'persistence failed') || 1
        new TaskOutcome.Paused(FINAL_STATE, 'build') || 0
        new TaskOutcome.Escalated(FINAL_STATE, new EscalationReport.AttemptsExhausted(3)) || 0
    }
}
