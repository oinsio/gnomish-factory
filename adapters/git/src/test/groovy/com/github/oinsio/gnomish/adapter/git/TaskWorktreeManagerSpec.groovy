package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.RegisteredCloneFixture
import com.github.oinsio.gnomish.app.project.RegisteredClone
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR6, FR8 of add-git-workflow (design D6): worktree create-or-reuse in the registered clone's own
 * worktree folder, {@code projects/<name>/worktrees/<clone>/<sanitized-taskId>/} under the factory
 * home (FR9, NFR-R2 of add-project-registry), and materializing the worktree on resume when it is
 * missing locally.
 */
class TaskWorktreeManagerSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()

    Path home
    Path cloneDir
    RegisteredClone registeredClone
    def manager

    def setup() {
        home = tempDir.resolve('home')
        cloneDir = seededClone(tempDir, 'my-project')
        registeredClone = RegisteredCloneFixture.registered(home, cloneDir)
        manager = new TaskWorktreeManager(runner, registeredClone)
    }

    private Path seededClone(Path parent, String name) {
        Files.createDirectories(parent)
        def clone = initWorkingRepo(parent, name)
        new File(clone.toFile(), 'a.txt').text = 'first'
        runner.run(clone, 'add', 'a.txt')
        runner.run(clone, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', 'init')
        clone
    }

    /** The clone's worktree folder, spelled out from the layout rather than read from the value. */
    private Path cloneFolder() {
        home.resolve('projects').resolve('widgets').resolve('worktrees').resolve('my-project')
    }

    def "FR6: fresh creation materializes the worktree at the deterministic path"() {
        given:
        def branchName = createTaskBranch(cloneDir, 'PROJ-1')

        when:
        Path path = manager.ensureWorktree('PROJ-1', branchName)

        then:
        path == cloneFolder().resolve('PROJ-1')
        path.toFile().isDirectory()

        and: 'FR6: the branch is checked out there'
        def head = runner.run(path, 'rev-parse', '--abbrev-ref', 'HEAD')
        head.stdout().forParsing().trim() == branchName
    }

    def "FR6: worktree directory name is sanitized and distinct from the branch name"() {
        given:
        def branchName = createTaskBranch(cloneDir, 'PROJ 42: fix/it')

        when:
        def path = manager.ensureWorktree('PROJ 42: fix/it', branchName)

        then:
        path == cloneFolder().resolve('PROJ-42-fix-it')
        !path.toString().contains('gnomish/')
        branchName == 'gnomish/PROJ-42-fix-it'
    }

    def "FR8: resume without a local worktree materializes it at the standard location"() {
        given: 'a first call creates the worktree, simulating an earlier run'
        def branchName = createTaskBranch(cloneDir, 'PROJ-2')
        def firstPath = manager.ensureWorktree('PROJ-2', branchName)
        assert firstPath.toFile().isDirectory()

        and: 'the worktree is removed, simulating a fresh machine that only has the branch'
        runner.run(cloneDir, 'worktree', 'remove', '--force', firstPath.toString())
        assert !firstPath.toFile().exists()

        when: 'resume calls ensureWorktree again with the same taskId/branch'
        Path resumedPath = manager.ensureWorktree('PROJ-2', branchName)

        then:
        resumedPath == firstPath
        resumedPath.toFile().isDirectory()
        def head = runner.run(resumedPath, 'rev-parse', '--abbrev-ref', 'HEAD')
        head.stdout().forParsing().trim() == branchName
    }

    def "FR6: reuse — calling ensureWorktree twice does not error and returns the same path"() {
        given:
        def branchName = createTaskBranch(cloneDir, 'PROJ-3')
        def firstPath = manager.ensureWorktree('PROJ-3', branchName)
        def markerFile = firstPath.resolve('marker.txt').toFile()
        markerFile.text = 'still here'

        when:
        def secondPath = manager.ensureWorktree('PROJ-3', branchName)

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
        def branchName = createTaskBranch(cloneDir, 'PROJ-6')
        def strayPath = cloneFolder().resolve('PROJ-6')
        Files.createDirectories(strayPath)

        when:
        def path = manager.ensureWorktree('PROJ-6', branchName)

        then: 'git worktree add actually ran and registered the path as a real worktree'
        path == strayPath
        def list = runner.run(cloneDir, 'worktree', 'list', '--porcelain').stdout()
        list.contains(strayPath.toRealPath().toString())
    }

    def "FR7-style: worktree creation does not alter the clone's own branch, HEAD, or working tree"() {
        given:
        def branchName = createTaskBranch(cloneDir, 'PROJ-4')
        def branchBefore = runner.run(cloneDir, 'rev-parse', '--abbrev-ref', 'HEAD').stdout().forParsing().trim()
        def headBefore = runner.run(cloneDir, 'rev-parse', 'HEAD').stdout().forParsing().trim()

        when:
        manager.ensureWorktree('PROJ-4', branchName)

        then:
        def branchAfter = runner.run(cloneDir, 'rev-parse', '--abbrev-ref', 'HEAD').stdout().forParsing().trim()
        def headAfter = runner.run(cloneDir, 'rev-parse', 'HEAD').stdout().forParsing().trim()
        def status = runner.run(cloneDir, 'status', '--porcelain')

        branchAfter == branchBefore
        headAfter == headBefore
        status.stdout().forParsing().trim().isEmpty()
    }

    def "FR6: parent directories of the clone's worktree folder are created as needed"() {
        given: 'a factory home whose folders do not exist yet'
        def deepHome = tempDir.resolve('does').resolve('not').resolve('exist').resolve('yet')
        def deepClone = RegisteredCloneFixture.unregistered(deepHome, cloneDir)
        def deepManager = new TaskWorktreeManager(runner, deepClone)
        def branchName = createTaskBranch(cloneDir, 'PROJ-5')

        when:
        def path = deepManager.ensureWorktree('PROJ-5', branchName)

        then:
        path.toFile().isDirectory()
        path == deepClone.worktrees().resolve('PROJ-5')
    }

    def "FR9, NFR-R2 of add-project-registry: same-named clones of different projects do not collide"() {
        given: '~/work/api registered to billing and ~/oss/api to gateway — two clones with one folder name'
        def work = seededClone(tempDir.resolve('work'), 'api')
        def oss = seededClone(tempDir.resolve('oss'), 'api')
        def billing = new TaskWorktreeManager(runner, RegisteredCloneFixture.registered(home, work, 'billing'))
        def gateway = new TaskWorktreeManager(runner, RegisteredCloneFixture.registered(home, oss, 'gateway'))

        when: 'both work a task with the same id'
        def billingPath = billing.ensureWorktree('PROJ-7', createTaskBranch(work, 'PROJ-7'))
        def gatewayPath = gateway.ensureWorktree('PROJ-7', createTaskBranch(oss, 'PROJ-7'))

        then: 'each worktree lives under its own project'
        billingPath == home.resolve('projects/billing/worktrees/api/PROJ-7')
        gatewayPath == home.resolve('projects/gateway/worktrees/api/PROJ-7')

        and: 'each is a worktree of its own clone'
        runner.run(work, 'worktree', 'list', '--porcelain').stdout().forParsing()
                .contains(billingPath.toRealPath().toString())
        runner.run(oss, 'worktree', 'list', '--porcelain').stdout().forParsing()
                .contains(gatewayPath.toRealPath().toString())
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
        def blockedHome = blockerFile.resolve('home-under-a-file')
        def blockedManager = new TaskWorktreeManager(runner, RegisteredCloneFixture.unregistered(blockedHome, cloneDir))
        def branchName = createTaskBranch(cloneDir, 'PROJ-9')

        when:
        blockedManager.ensureWorktree('PROJ-9', branchName)

        then:
        def ex = thrown(UncheckedIOException)
        ex.message.contains('failed to create worktree parent directories')
    }
}
