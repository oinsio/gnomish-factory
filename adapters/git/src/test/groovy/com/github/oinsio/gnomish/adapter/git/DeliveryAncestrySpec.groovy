package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.TaskContext
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.ToolCall
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR1 of harden-task-branch-contract: delivery is a fact about the task's own branch. A cleanup
 * commit an earlier task left in the base — its branch merged with history — is not this task's
 * delivery, so a live task forked from that base must not read as delivered.
 */
class DeliveryAncestrySpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    Path worktreesRoot
    GitTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        commitAll(cloneDir)
        worktreesRoot = tempDir.resolve('worktrees')
        repository = new GitTaskRepository(runner, cloneDir, worktreesRoot, ClaimEpochSource.NONE)
    }

    private void create(String taskId) {
        repository.createTask(
                new TaskContext(taskId, UntrustedText.tracker('Fix'), UntrustedText.tracker('Body'), []),
                TaskStart.commit(cloneDir, 'HEAD'),
                TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD),
                TaskState.atStageStart('implement'))
    }

    /** Runs PROJ-1 to delivery and merges its branch into the base with its history (no squash). */
    private void deliverAndMergeEarlierTask() {
        create('PROJ-1')
        def worktree = worktreesRoot.resolve('clone').resolve('PROJ-1')
        def trace = new ToolTrace(new AttemptKey('PROJ-1', 'implement', 0),
                [
                    new ToolCall(0, 'bash', Instant.parse('2026-07-18T09:00:00Z'), Duration.ofMillis(50))
                ])
        new GitAttemptPersistence(runner, worktree, 'PROJ-1', ClaimEpochSource.NONE)
                .persist('PROJ-1', TaskState.atStageStart('implement'), trace)
        repository.recordOutcome('PROJ-1', new TaskOutcome.Completed(TaskState.atStageStart('implement')))
        repository.finishCleanup('PROJ-1')
        def merge = runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a',
                'merge', '--no-ff', '-m', 'Merge PR: PROJ-1', 'gnomish/PROJ-1')
        assert merge.exitCode() == 0
    }

    def "FR1: a live task forked from a base that holds an earlier task's cleanup is not delivered"() {
        given: 'an earlier task delivered and merged into the base with its history'
        deliverAndMergeEarlierTask()

        when: 'a new task starts from that base'
        create('PROJ-2')

        then: 'the new branch has no cleanup commit of its own'
        !new RefTipSource(runner, cloneDir, 'gnomish/PROJ-2').cleanupCommitInHistory()
        new GitShowTip(runner, cloneDir, 'gnomish/PROJ-2').cleanupCommit().isEmpty()
    }

    def "FR1: a base with an earlier task's cleanup merged into a live task branch does not deliver it"() {
        given: 'a live task, and an earlier task delivered and merged into the base afterwards'
        create('PROJ-2')
        deliverAndMergeEarlierTask()

        when: 'the base is merged into the live task branch'
        def worktree = worktreesRoot.resolve('clone').resolve('PROJ-2')
        def merge = runner.run(worktree, '-c', 'user.email=a@b.c', '-c', 'user.name=a',
                'merge', '--no-ff', '-m', 'Merge base into PROJ-2', currentBranch(cloneDir))
        assert merge.exitCode() == 0

        then:
        !new RefTipSource(runner, cloneDir, 'gnomish/PROJ-2').cleanupCommitInHistory()
    }

    def "FR1: a branch with no STARTED commit of its own is not delivered by its base"() {
        given: 'an earlier task delivered and merged into the base'
        deliverAndMergeEarlierTask()

        when: 'a bare branch points at that base'
        assert runner.run(cloneDir, 'branch', 'gnomish/PROJ-3', 'HEAD').exitCode() == 0

        then:
        !new RefTipSource(runner, cloneDir, 'gnomish/PROJ-3').cleanupCommitInHistory()
    }

    def "FR1: the earlier task's own branch still reads as delivered"() {
        when:
        deliverAndMergeEarlierTask()

        then:
        new RefTipSource(runner, cloneDir, 'gnomish/PROJ-1').cleanupCommitInHistory()
    }
}
