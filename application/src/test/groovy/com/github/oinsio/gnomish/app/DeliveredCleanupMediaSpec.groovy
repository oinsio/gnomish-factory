package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.domain.branch.BranchShape
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR4 of fix-terminal-receipt-convergence (design D2): the delivered cleanup over each medium's
 * existing writers — the host's registered-clone store and worktree disposal, the container
 * support's bare-object store and box disposal. The cleanup's own decisions are
 * {@code DeliveredCleanupSpec}'s.
 */
class DeliveredCleanupMediaSpec extends Specification {

    private static final BranchAdoption.AlreadyLocal BRANCH = new BranchAdoption.AlreadyLocal('PROJ 1')

    private static final Path CLONE_DIR = Path.of('/clones/widgets')

    TaskBranchGit branches = Mock(TaskBranchGit)
    TaskLifecycleStore store = Mock(TaskLifecycleStore)
    TaskEnvironmentDisposal disposal = Mock(TaskEnvironmentDisposal)

    // FR4, design D2: the host medium cleans through the clone's store and removes its worktree
    def "FR4: the host medium commits through the registered clone's store and disposes its worktree"() {
        given:
        def clone = RegisteredCloneFixture.unregistered(Path.of('/tmp/gnomish-home'), CLONE_DIR)
        def storeGit = Mock(TaskStoreGit)
        def worktrees = Mock(TaskWorktreeGit)
        def git = new TaskGit(storeGit, branches, worktrees, new ClaimEpochBook())

        when:
        DeliveredCleanup.host(git, clone).cleanUp(CLONE_DIR, BRANCH)

        then:
        1 * storeGit.taskRepository(clone) >> store
        1 * worktrees.environmentDisposal(clone) >> disposal
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1') >> new BranchShape.CompletedUncleaned()
        1 * store.finishCleanup('PROJ 1')
        1 * disposal.dispose('PROJ-1')
    }

    // FR4, design D2: the container medium cleans through the support's store and disposes its box
    def "FR4: the container medium commits through the support's store and disposes its box"() {
        given:
        def support = Mock(SandboxRunSupport)

        when:
        DeliveredCleanup.container(branches, support).cleanUp(CLONE_DIR, BRANCH)

        then:
        1 * support.taskRepository() >> store
        1 * branches.classifyShape(CLONE_DIR, 'PROJ 1') >> new BranchShape.CompletedUncleaned()
        1 * store.finishCleanup('PROJ 1')

        then:
        1 * support.disposeExistingEnvironment()
    }
}
