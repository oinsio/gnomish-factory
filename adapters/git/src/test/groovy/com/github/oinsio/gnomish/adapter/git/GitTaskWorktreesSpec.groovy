package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.DivergenceOutcome
import com.github.oinsio.gnomish.app.port.git.WorktreeSalvager
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal
import com.github.oinsio.gnomish.domain.engine.TaskOutcome
import com.github.oinsio.gnomish.domain.engine.TaskState
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR12b of split-into-modules: {@code GitTaskWorktrees} is the worktree-side delegation facade the
 * application layer binds as {@code TaskWorktreeGit}. Like its branch-side twin it holds no logic,
 * so this spec asserts each port method reaches its collaborator and returns what that
 * collaborator produced.
 *
 * <p>Added by task 8.1 of split-into-modules for the same reason as {@link GitTaskBranchesSpec}:
 * per-module mutation scoping needs a module's classes covered by that module's own specs, and
 * this facade was previously driven only by the composition root's suites in {@code :bootstrap}.
 */
class GitTaskWorktreesSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def worktrees = new GitTaskWorktrees(runner, ClaimEpochSource.NONE)
    Path cloneDir
    Path worktreesRoot

    def setup() {
        cloneDir = initWorkingRepo(tempDir, 'clone')
        Files.writeString(cloneDir.resolve('a.txt'), 'first')
        commitAll(cloneDir, 'init')
        worktreesRoot = tempDir.resolve('worktrees')
    }

    private String createTaskBranch(String taskId) {
        def result = new TaskBranchCreator(runner).createBranch(cloneDir, taskId, null)
        (result as BranchCreationResult.Created).branchName()
    }

    def "ensureWorktree delegates to TaskWorktreeManager and returns the materialized path"() {
        given:
        def branch = createTaskBranch('PROJ-1')

        when:
        def path = worktrees.ensureWorktree(cloneDir, worktreesRoot, 'PROJ-1', branch)

        then:
        path == worktreesRoot.resolve('clone').resolve('PROJ-1')
        Files.isDirectory(path)
    }

    def "reconcile delegates to the replica-pair reconciler and returns its verdict"() {
        given:
        def branch = createTaskBranch('PROJ-2')
        def worktree = worktrees.ensureWorktree(cloneDir, worktreesRoot, 'PROJ-2', branch)

        expect: 'no remote-tracking ref to diverge from — the check reports the local tip stands'
        worktrees.reconcile(worktree, 'PROJ-2', branch) == DivergenceOutcome.NO_REMOTE_TRACKING_REF
    }

    def "salvage delegates to WorktreeSalvage and returns a salvager bound to the worktree"() {
        given:
        def branch = createTaskBranch('PROJ-3')
        def worktree = worktrees.ensureWorktree(cloneDir, worktreesRoot, 'PROJ-3', branch)
        Files.writeString(worktree.resolve('leftover.txt'), 'uncommitted')

        when:
        WorktreeSalvager salvager = worktrees.salvage(worktree)

        then:
        salvager.hasLeftovers()
    }

    def "cleanUp delegates to TaskWorktreeCleanup — a completed task's worktree is removed"() {
        given:
        def branch = createTaskBranch('PROJ-4')
        def worktree = worktrees.ensureWorktree(cloneDir, worktreesRoot, 'PROJ-4', branch)
        assert Files.isDirectory(worktree)

        when:
        worktrees.cleanUp(cloneDir, worktree, new TaskOutcome.Completed(TaskState.atStageStart('implement')))

        then:
        !Files.exists(worktree)
    }

    def "pruneWorktrees delegates to TaskWorktreeCleanup — a stale administrative entry is dropped"() {
        given: 'a worktree whose directory was removed behind git\'s back'
        def branch = createTaskBranch('PROJ-5')
        def worktree = worktrees.ensureWorktree(cloneDir, worktreesRoot, 'PROJ-5', branch)
        worktree.toFile().deleteDir()
        assert runner.run(cloneDir, 'worktree', 'list').stdout().contains('PROJ-5')

        when:
        worktrees.pruneWorktrees(cloneDir)

        then:
        !runner.run(cloneDir, 'worktree', 'list').stdout().contains('PROJ-5')
    }

    def "environmentDisposal delegates to WorktreeEnvironmentDisposal and returns the bound port"() {
        expect:
        worktrees.environmentDisposal(cloneDir, worktreesRoot) instanceof TaskEnvironmentDisposal
    }
}
