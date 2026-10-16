package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.port.git.BranchAdoption
import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR8 of fix-terminal-receipt-convergence (design D10, D11): {@link TaskWorktreeManager}
 * materializes a worktree only over an existing local branch, through either entry — the port's
 * typed one and the host store's task-id one — and never lets git create the branch from a
 * same-named remote-tracking ref. Real git, a clone that holds the branch only as a tracking ref.
 */
class TaskWorktreeExistingBranchSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    Path home
    Path cloneDir
    TaskWorktreeManager manager

    def setup() {
        home = tempDir.resolve('home')
        Files.createDirectories(tempDir)
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        RegisteredClone registered = RegisteredCloneFixture.registered(home, cloneDir)
        manager = new TaskWorktreeManager(runner, registered)
    }

    private Path cloneFolder() {
        home.resolve('projects').resolve('widgets').resolve('worktrees').resolve('my-project')
    }

    /** A clone holding {@code gnomish/<taskId>} only as a remote-tracking ref, as a second clone of origin does. */
    private void trackingOnly(String taskId) {
        def head = runner.run(cloneDir, 'rev-parse', 'HEAD').stdout().forParsing().trim()
        assert runner.run(cloneDir, 'update-ref', TaskBranchRefs.tracking(taskId), head).exitCode() == 0
    }

    // FR8 of fix-terminal-receipt-convergence (design D10, D11): the worktree names an existing
    // local branch; git's own fallback — creating it from a same-named remote-tracking ref — never
    // engages, so a branch is never made local anywhere but the adoption owner.
    def "FR8: an absent local branch fails the worktree, and no branch is created from the tracking ref"() {
        given:
        trackingOnly('PROJ-8')

        when:
        manager.ensureWorktree(new BranchAdoption.AlreadyLocal('PROJ-8'))

        then:
        def ex = thrown(WorktreeCreationFailedException)
        ex.message.contains('PROJ-8')
        ex.message.contains(TaskBranchRefs.head('PROJ-8'))

        and: 'no local branch and no worktree appeared'
        runner.run(cloneDir, 'rev-parse', '--verify', '--quiet', TaskBranchRefs.head('PROJ-8')).exitCode() != 0
        !cloneFolder().resolve('PROJ-8').toFile().exists()
    }

    // FR8 of fix-terminal-receipt-convergence: the host store's task-id entry shares the same
    // materialization, so it refuses an absent local branch exactly as the port's entry does.
    def "FR8: the store's entry refuses an absent local branch the same way"() {
        given:
        trackingOnly('PROJ-10')

        when:
        manager.storeWorktree('PROJ-10')

        then:
        thrown(WorktreeCreationFailedException)
        runner.run(cloneDir, 'rev-parse', '--verify', '--quiet', TaskBranchRefs.head('PROJ-10')).exitCode() != 0
    }

    // FR8 of fix-terminal-receipt-convergence: over an existing local branch the store's entry
    // materializes and then reuses, as the port's entry does.
    def "FR8: the store's entry materializes over an existing local branch, then reuses it"() {
        given:
        def branchName = createTaskBranch(cloneDir, 'PROJ-11')

        when:
        def path = manager.storeWorktree('PROJ-11')

        then:
        path == cloneFolder().resolve('PROJ-11')
        runner.run(path, 'rev-parse', '--abbrev-ref', 'HEAD').stdout().forParsing().trim() == branchName
        manager.storeWorktree('PROJ-11') == path
    }
}
