package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.EscalationReport
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6 of add-git-workflow (design D6): cleanup by outcome — Completed removes the worktree
 * (branch stays), Escalated/Paused/Aborted keep it as-is — and {@code git worktree prune} as
 * separate, unconditional git housekeeping.
 */
class TaskWorktreeCleanupSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def branchCreator = new TaskBranchCreator(runner)
    def worktreeManager
    def cleanup

    Path worktreesRoot
    Path cloneDir

    private static TaskState sampleState() {
        TaskState.atStageStart('build')
    }

    def setup() {
        worktreesRoot = tempDir.resolve('worktrees-root')
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        worktreeManager = new TaskWorktreeManager(runner, worktreesRoot)
        cleanup = new TaskWorktreeCleanup(runner)
    }

    private Path setUpWorktree(String taskId) {
        def result = branchCreator.createBranch(cloneDir, taskId, null)
        def branchName = (result as BranchCreationResult.Created).branchName()
        worktreeManager.ensureWorktree(cloneDir, taskId, branchName)
    }

    def "FR6: Completed removes the worktree but leaves the branch intact"() {
        given:
        def path = setUpWorktree('PROJ-1')
        assert path.toFile().isDirectory()

        when:
        cleanup.cleanUp(cloneDir, path, new TaskOutcome.Completed(sampleState()))

        then: 'the worktree directory is gone'
        !path.toFile().exists()

        and: 'git no longer registers it as a worktree'
        def list = runner.run(cloneDir, 'worktree', 'list', '--porcelain')
        !list.stdout().contains(path.toString())

        and: 'the branch itself still exists'
        def branches = runner.run(cloneDir, 'branch', '--list', 'gnomish/PROJ-1')
        branches.stdout().contains('gnomish/PROJ-1')
    }

    def "FR6: Paused keeps the worktree untouched"() {
        given:
        def path = setUpWorktree('PROJ-2')
        def marker = path.resolve('marker.txt').toFile()
        marker.text = 'still here'

        when:
        cleanup.cleanUp(cloneDir, path, new TaskOutcome.Paused(sampleState(), 'build'))

        then:
        path.toFile().isDirectory()
        marker.exists()
        marker.text == 'still here'
    }

    def "FR6: Escalated keeps the worktree untouched"() {
        given:
        def path = setUpWorktree('PROJ-3')
        def marker = path.resolve('marker.txt').toFile()
        marker.text = 'still here'
        def report = new EscalationReport.AttemptsExhausted(3)

        when:
        cleanup.cleanUp(cloneDir, path, new TaskOutcome.Escalated(sampleState(), report))

        then:
        path.toFile().isDirectory()
        marker.exists()
        marker.text == 'still here'
    }

    def "FR6: Aborted always keeps the worktree, even unconditionally"() {
        given:
        def path = setUpWorktree('PROJ-4')
        def marker = path.resolve('marker.txt').toFile()
        marker.text = 'unsaved work'
        def failedAt = new AttemptKey('PROJ-4', 'build', 0)

        when:
        cleanup.cleanUp(cloneDir, path, new TaskOutcome.Aborted(sampleState(), failedAt, 'persist failed'))

        then: 'the hard "always keep" rule holds — no path accidentally matches the removal branch'
        path.toFile().isDirectory()
        marker.exists()
        marker.text == 'unsaved work'
    }

    def "FR6: cleaning up an already-removed Completed worktree does not crash"() {
        given:
        def path = setUpWorktree('PROJ-5')
        cleanup.cleanUp(cloneDir, path, new TaskOutcome.Completed(sampleState()))
        assert !path.toFile().exists()

        when: 'cleanup runs a second time for the same task, e.g. on a resumed run'
        cleanup.cleanUp(cloneDir, path, new TaskOutcome.Completed(sampleState()))

        then:
        noExceptionThrown()
        !path.toFile().exists()
    }

    def "FR6: git worktree prune drops registrations for directories deleted outside git"() {
        given:
        def path = setUpWorktree('PROJ-6')
        assert path.toFile().isDirectory()

        and: 'the worktree directory is deleted by plain filesystem removal, not git worktree remove'
        path.toFile().deleteDir()
        def before = runner.run(cloneDir, 'worktree', 'list', '--porcelain')
        assert before.stdout().contains(path.toString())

        when:
        cleanup.pruneWorktrees(cloneDir)

        then:
        def after = runner.run(cloneDir, 'worktree', 'list', '--porcelain')
        !after.stdout().contains(path.toString())
    }
}
