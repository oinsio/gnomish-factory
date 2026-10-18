package com.github.oinsio.gnomish.app

import com.github.oinsio.gnomish.app.lease.ClaimEpochBook
import com.github.oinsio.gnomish.app.port.console.fake.ScriptedConsoleIO
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit
import com.github.oinsio.gnomish.app.port.git.TaskGit
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore
import com.github.oinsio.gnomish.app.port.git.TaskStoreGit
import com.github.oinsio.gnomish.app.port.git.TaskWorktreeGit
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.TaskState
import com.github.oinsio.gnomish.domain.engine.Verdict
import com.github.oinsio.gnomish.domain.engine.fake.ScriptedExecutor
import java.nio.file.Path
import spock.lang.Specification

/**
 * FR8 of fix-terminal-receipt-convergence (design D11): every resume bootstrap takes the task's
 * local branch from the adoption owner before anything writes, and hands the owner's typed answer
 * on — the host bootstrap ({@link TakeResumeBootstrap}, behind both {@code take} and {@code run
 * --resume}) adopts over the location its own lookup refreshed, then materializes the worktree over
 * that branch; the two container bootstraps switch on {@link TaskBranchGit#ensureLocalTaskBranch}'s
 * answer. Driven on port mocks, so the order of the calls is what is asserted.
 */
class ResumeBootstrapAdoptionSpec extends Specification implements RunChainFakes {

    static final BranchLocation TRACKING = new BranchLocation.RemoteTracking('refs/remotes/origin/gnomish/PROJ-1')
    static final BranchAdoption.Adopted ADOPTED = new BranchAdoption.Adopted('PROJ-1')
    static final Path WORKTREE = Path.of('/tmp/gnomish-worktrees/PROJ-1')

    TaskBranchGit branches = Mock(TaskBranchGit)
    TaskWorktreeGit worktrees = Mock(TaskWorktreeGit)
    TaskStoreGit store = Stub(TaskStoreGit) {
        readTaskRecord(WORKTREE, 'PROJ-1') >> Optional.of(freshRecord())
    }

    private TaskGit git() {
        new TaskGit(store, branches, worktrees, new ClaimEpochBook())
    }

    // Scenario: git/git-task-persistence / The host worktree is added on an existing local branch
    def "FR8: the host bootstrap adopts a remote-tracking-only branch, then materializes the worktree over it"() {
        when:
        def bundle = new TakeResumeBootstrap(git(), CLONE, 'taskId').bootstrap(CLONE_DIR, 'PROJ-1')

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> TRACKING

        then: 'the adoption runs over the location the lookup answered — no second lookup'
        1 * branches.adopt(CLONE_DIR, 'PROJ-1', TRACKING) >> ADOPTED

        then: 'the worktree is handed the owner answer, never a name for git to resolve'
        1 * worktrees.ensureWorktree(CLONE, ADOPTED) >> WORKTREE

        then:
        1 * worktrees.reconcile(WORKTREE, 'PROJ-1', 'gnomish/PROJ-1')
        0 * branches.ensureLocalTaskBranch(_, _)

        and: 'the bundle carries the same answer to the writers behind it'
        bundle.get().branch().is(ADOPTED)
        bundle.get().branchName() == 'gnomish/PROJ-1'
    }

    def "FR8: run --resume bootstraps through the same adoption"() {
        given:
        def runner = new GitResumeRunner(assemblyRunning(new ScriptedExecutor([])), git(), CLONE, 'taskId')

        when:
        def bundle = runner.bootstrap(CLONE_DIR, 'PROJ-1')

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> TRACKING

        then:
        1 * branches.adopt(CLONE_DIR, 'PROJ-1', TRACKING) >> ADOPTED

        then:
        1 * worktrees.ensureWorktree(CLONE, ADOPTED) >> WORKTREE

        and:
        bundle.get().branch().is(ADOPTED)
    }

    def "FR8: a branch the owner finds nowhere is the usage error, and no worktree is materialized"() {
        when:
        new TakeResumeBootstrap(git(), CLONE, 'taskId').bootstrap(CLONE_DIR, 'PROJ-1')

        then:
        1 * branches.locate(CLONE_DIR, 'PROJ-1') >> new BranchLocation.NotFound()
        1 * branches.adopt(CLONE_DIR, 'PROJ-1', new BranchLocation.NotFound()) >> new BranchAdoption.Nowhere()

        and:
        def ex = thrown(UsageException)
        ex.message.contains('PROJ-1')
        0 * worktrees.ensureWorktree(_, _)
    }

    def "FR8: the container take bootstrap hands the owner's answer on in its bundle"() {
        given:
        def support = Stub(SandboxRunSupport) {
            readTaskJson() >> freshRecord()
        }
        def containers = new ContainerTakeSupport(null, { cloneDir, taskId, segments, definition, creds ->
            support
        } as ContainerSupportFactory)

        when:
        def bundle = new TakeContainerResumeBootstrap(git(), containers, 'taskId')
                .bootstrap(CLONE_DIR, 'PROJ-1', [], completingPipeline())

        then:
        1 * branches.ensureLocalTaskBranch(CLONE_DIR, 'PROJ-1') >> ADOPTED

        and:
        bundle.branch().is(ADOPTED)
        bundle.branchName() == 'gnomish/PROJ-1'
    }

    def "FR8: run --sandbox --resume cleans a completed tip over the branch the owner adopted"() {
        given:
        def boxStore = Mock(TaskLifecycleStore)
        def support = Mock(SandboxRunSupport) {
            taskRepository() >> boxStore
            readTaskJson() >> recordWith(new RecordedOutcome.Completed())
            readStateOrInitial(_) >> TaskState.atStageStart('build')
        }
        def console = new ScriptedConsoleIO()
        def runner = new ContainerResumeRunner(
                assemblyRunningLoop(new ScriptedExecutor([]), console, new Verdict.Pass(), [], []),
                git(), 'taskId', { _c, _t, _s, _def, _cred ->
                    support
                } as ContainerSupportFactory)

        when:
        runner.run(new RunOrder(CLONE_DIR, null, completingPipeline(), false), 'PROJ-1', null, [])

        then:
        1 * branches.ensureLocalTaskBranch(CLONE_DIR, 'PROJ-1') >> ADOPTED

        then:
        2 * branches.classifyShape(CLONE_DIR, 'PROJ-1') >> new BranchShape.CompletedUncleaned()
        1 * boxStore.finishCleanup('PROJ-1')
        1 * support.disposeExistingEnvironment()
    }
}
