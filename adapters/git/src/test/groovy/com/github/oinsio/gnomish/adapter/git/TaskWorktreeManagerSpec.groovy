package com.github.oinsio.gnomish.adapter.git

import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR8 of add-git-workflow (design D6): worktree create-or-reuse under
 * {@code <worktreesRoot>/<project-name>/<sanitized-taskId>/}, and materializing the worktree
 * on resume when it is missing locally.
 */
class TaskWorktreeManagerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def branchCreator = new TaskBranchCreator(runner)

    Path worktreesRoot
    Path cloneDir
    def manager

    def setup() {
        worktreesRoot = tempDir.resolve('worktrees-root')
        cloneDir = initWorkingRepo(tempDir, 'my-project')
        new File(cloneDir.toFile(), 'a.txt').text = 'first'
        runner.run(cloneDir, 'add', 'a.txt')
        runner.run(cloneDir, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        manager = new TaskWorktreeManager(runner, worktreesRoot)
    }

    private String createTaskBranch(String taskId) {
        def result = branchCreator.createBranch(cloneDir, taskId, null)
        (result as BranchCreationResult.Created).branchName()
    }

    def "FR6: fresh creation materializes the worktree at the deterministic path"() {
        given:
        def branchName = createTaskBranch('PROJ-1')

        when:
        def path = manager.ensureWorktree(cloneDir, 'PROJ-1', branchName)

        then:
        path == worktreesRoot.resolve('my-project').resolve('PROJ-1')
        path.toFile().isDirectory()

        and: 'FR6: the branch is checked out there'
        def head = runner.run(path, 'rev-parse', '--abbrev-ref', 'HEAD')
        head.stdout().trim() == branchName
    }

    def "FR6: worktree directory name is sanitized and distinct from the branch name"() {
        given:
        def branchName = createTaskBranch('PROJ 42: fix/it')

        when:
        def path = manager.ensureWorktree(cloneDir, 'PROJ 42: fix/it', branchName)

        then:
        path == worktreesRoot.resolve('my-project').resolve('PROJ-42-fix-it')
        !path.toString().contains('gnomish/')
        branchName == 'gnomish/PROJ-42-fix-it'
    }

    def "FR8: resume without a local worktree materializes it at the standard location"() {
        given: 'a first call creates the worktree, simulating an earlier run'
        def branchName = createTaskBranch('PROJ-2')
        def firstPath = manager.ensureWorktree(cloneDir, 'PROJ-2', branchName)
        assert firstPath.toFile().isDirectory()

        and: 'the worktree is removed, simulating a fresh machine that only has the branch'
        runner.run(cloneDir, 'worktree', 'remove', '--force', firstPath.toString())
        assert !firstPath.toFile().exists()

        when: 'resume calls ensureWorktree again with the same taskId/branch'
        def resumedPath = manager.ensureWorktree(cloneDir, 'PROJ-2', branchName)

        then:
        resumedPath == firstPath
        resumedPath.toFile().isDirectory()
        def head = runner.run(resumedPath, 'rev-parse', '--abbrev-ref', 'HEAD')
        head.stdout().trim() == branchName
    }

    def "FR6: reuse — calling ensureWorktree twice does not error and returns the same path"() {
        given:
        def branchName = createTaskBranch('PROJ-3')
        def firstPath = manager.ensureWorktree(cloneDir, 'PROJ-3', branchName)
        def markerFile = firstPath.resolve('marker.txt').toFile()
        markerFile.text = 'still here'

        when:
        def secondPath = manager.ensureWorktree(cloneDir, 'PROJ-3', branchName)

        then:
        noExceptionThrown()
        secondPath == firstPath
        markerFile.exists()
        markerFile.text == 'still here'
    }

    // PIT BooleanTrueReturnValsMutator on isRegisteredWorktree/its anyMatch lambda: a directory
    // that exists at the deterministic path but is NOT a registered git worktree (a stray leftover)
    // must not be reused as-is — ensureWorktree must still register it via `git worktree add`.
    def "FR6: a stray directory at the deterministic path that is not a registered worktree is not reused"() {
        given: 'an empty directory occupies the deterministic path, never registered via git worktree add'
        def branchName = createTaskBranch('PROJ-6')
        def strayPath = worktreesRoot.resolve('my-project').resolve('PROJ-6')
        Files.createDirectories(strayPath)

        when:
        def path = manager.ensureWorktree(cloneDir, 'PROJ-6', branchName)

        then: 'git worktree add actually ran and registered the path as a real worktree'
        path == strayPath
        def list = runner.run(cloneDir, 'worktree', 'list', '--porcelain').stdout()
        list.contains(strayPath.toRealPath().toString())
    }

    def "FR7-style: worktree creation does not alter the clone's own branch, HEAD, or working tree"() {
        given:
        def branchName = createTaskBranch('PROJ-4')
        def branchBefore = runner.run(cloneDir, 'rev-parse', '--abbrev-ref', 'HEAD').stdout().trim()
        def headBefore = runner.run(cloneDir, 'rev-parse', 'HEAD').stdout().trim()

        when:
        manager.ensureWorktree(cloneDir, 'PROJ-4', branchName)

        then:
        def branchAfter = runner.run(cloneDir, 'rev-parse', '--abbrev-ref', 'HEAD').stdout().trim()
        def headAfter = runner.run(cloneDir, 'rev-parse', 'HEAD').stdout().trim()
        def status = runner.run(cloneDir, 'status', '--porcelain')

        branchAfter == branchBefore
        headAfter == headBefore
        status.stdout().trim().isEmpty()
    }

    def "FR6: parent directories under the worktrees root are created as needed"() {
        given:
        def deepRoot = tempDir.resolve('does').resolve('not').resolve('exist').resolve('yet')
        def deepManager = new TaskWorktreeManager(runner, deepRoot)
        def branchName = createTaskBranch('PROJ-5')

        when:
        def path = deepManager.ensureWorktree(cloneDir, 'PROJ-5', branchName)

        then:
        path.toFile().isDirectory()
        path == deepRoot.resolve('my-project').resolve('PROJ-5')
    }

    // PIT VoidMethodCallMutator on ensureWorktree's createParentDirectories call: a real `git
    // worktree add` happens to auto-create missing parent directories itself in the common case,
    // masking this call's effect there. This scenario instead blocks a parent path component with
    // a plain file — createParentDirectories fails fast with its own UncheckedIOException; if the
    // call were removed, `git worktree add` would instead hit the same obstruction itself and fail
    // with a *different* exception (WorktreeCreationFailedException), so the exception type/message
    // distinguishes "our call ran" from "the call was skipped".
    def "FR6: a blocked parent path fails via createParentDirectories' own exception, not git's"() {
        given: 'a plain file occupies a path component the worktree\'s parent directory needs'
        def blockerFile = tempDir.resolve('blocker-file')
        Files.writeString(blockerFile, 'not a directory')
        def blockedRoot = blockerFile.resolve('worktrees-under-a-file')
        def blockedManager = new TaskWorktreeManager(runner, blockedRoot)
        def branchName = createTaskBranch('PROJ-9')

        when:
        blockedManager.ensureWorktree(cloneDir, 'PROJ-9', branchName)

        then:
        def ex = thrown(UncheckedIOException)
        ex.message.contains('failed to create worktree parent directories')
    }
}
