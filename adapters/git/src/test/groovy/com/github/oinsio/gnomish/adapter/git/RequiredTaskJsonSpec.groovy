package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2 of fix-envelope-medium, FR5 of harden-logging-observability: a required envelope read that
 * git refuses reports git's own reason, so an unborn {@code HEAD} or a broken object is not
 * reported to the operator as a plain "the tip carries no task.json".
 */
class RequiredTaskJsonSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    Path cloneDir
    GitTaskRepository repository

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        repository = new GitTaskRepository(runner, cloneDir, tempDir.resolve('worktrees'), ClaimEpochSource.NONE)
    }

    def "a lifecycle rewrite on a tip with no envelope reports git's own diagnosis, not just its absence"() {
        given: 'a task branch whose tip was never given a .gnomish-task/ envelope'
        runner.run(cloneDir, 'branch', 'gnomish/PROJ-9', 'HEAD')

        when:
        repository.recordOutcome('PROJ-9', new TaskOutcome.Completed(TaskState.atStageStart('implement')))

        then: 'the failure names the task, the path and the exit code'
        def e = thrown(GitTaskRepositoryException)
        e.message.contains('PROJ-9')
        e.message.contains('.gnomish-task/task.json')
        e.message.contains('git show exited 128')

        and: "git's own words survive into the operator's line"
        e.message.contains("does not exist in 'HEAD'")
    }
}
