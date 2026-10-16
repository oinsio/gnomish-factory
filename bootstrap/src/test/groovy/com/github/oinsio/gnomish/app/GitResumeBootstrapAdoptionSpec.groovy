package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.adapter.git.TaskStart
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.baseref.BaseRule
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files

/**
 * FR8 of fix-terminal-receipt-convergence (design D9, D11), on real git over a bare origin: a
 * clone that holds the task branch only as a remote-tracking ref — a second home of the factory,
 * or a clone whose local line was removed — resumes through the host bootstrap by adopting the
 * branch first, then materializing the worktree over the head the adoption created. Git is never
 * left to create the branch from the tracking ref inside {@code worktree add}.
 */
class GitResumeBootstrapAdoptionSpec extends GitResumeSpecBase {

    def "FR8: a remote-tracking-only branch is adopted at origin's tip, then materialized"() {
        given: 'a task whose branch and register reach origin through the factory\'s own closing push'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        gitOutput(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main')
        repository().createTask(context('PROJ-30'), TaskStart.commit(cloneDir, 'HEAD'),
                TaskStart.pin('HEAD', BaseRule.LOCAL_HEAD), TaskState.atStageStart('implement'))
        def worktree = expectedWorktree('PROJ-30')
        taskGit.branches().pushClosingBestEffort(worktree, 'PROJ-30')
        def originTip = gitOutput(bare, 'rev-parse', 'refs/heads/gnomish/PROJ-30').trim()

        and: 'this clone keeps no local line: the worktree and the head are gone'
        gitOutput(cloneDir, 'worktree', 'remove', '--force', worktree.toString())
        gitOutput(cloneDir, 'update-ref', '-d', 'refs/heads/gnomish/PROJ-30')

        when:
        def bundle = newResumeRunner(new ByteArrayInputStream(new byte[0]), System.out)
                .bootstrap(cloneDir, 'PROJ-30').get()

        then: 'the owner adopted the head at the tip origin holds'
        bundle.branch() == new BranchAdoption.Adopted('PROJ-30')
        gitOutput(cloneDir, 'rev-parse', 'refs/heads/gnomish/PROJ-30').trim() == originTip

        and: 'the worktree checks out that local branch and carries the task'
        Files.isDirectory(bundle.worktreePath())
        gitOutput(bundle.worktreePath(), 'rev-parse', '--abbrev-ref', 'HEAD').trim() == 'gnomish/PROJ-30'
        Files.exists(bundle.worktreePath().resolve('.gnomish-task').resolve('task.json'))
    }
}
