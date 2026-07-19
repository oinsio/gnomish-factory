package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR7 of add-git-workflow (design D7): branch creation from the clone's current state,
 * with an optional {@code --base <ref>} override, never fetching or pulling.
 */
class TaskBranchCreatorSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def creator = new TaskBranchCreator(runner)

    private String commit(Path repo, String fileName, String content) {
        new File(repo.toFile(), fileName).text = content
        runner.run(repo, 'add', fileName)
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', fileName)
        runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
    }

    def "FR7: branch created from current HEAD when no base ref is given"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def head = commit(repo, 'a.txt', 'first')

        when:
        def result = creator.createBranch(repo, 'PROJ-1', null)

        then:
        result instanceof BranchCreationResult.Created
        (result as BranchCreationResult.Created).baseCommit() == head
    }

    def "FR7: branch created from an explicit --base ref, not HEAD"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def older = commit(repo, 'a.txt', 'first')
        runner.run(repo, 'tag', 'v-old', older)
        commit(repo, 'b.txt', 'second')

        when:
        def result = creator.createBranch(repo, 'PROJ-2', 'v-old')

        then:
        result instanceof BranchCreationResult.Created
        (result as BranchCreationResult.Created).baseCommit() == older
    }

    def "FR2: branch name is sanitized via TaskIdSanitizer"() {
        given:
        def repo = initWorkingRepo(tempDir)
        commit(repo, 'a.txt', 'first')

        when:
        creator.createBranch(repo, 'PROJ 42: fix/it', null)

        then:
        def listed = runner.run(repo, 'branch', '--list', 'gnomish/PROJ-42-fix-it')
        listed.stdout().contains('gnomish/PROJ-42-fix-it')
    }

    def "FR7: branch-already-exists is reported as a deterministic result, not a crash"() {
        given:
        def repo = initWorkingRepo(tempDir)
        commit(repo, 'a.txt', 'first')
        creator.createBranch(repo, 'PROJ-3', null)

        when:
        def result = creator.createBranch(repo, 'PROJ-3', null)

        then:
        noExceptionThrown()
        result instanceof BranchCreationResult.AlreadyExists
        (result as BranchCreationResult.AlreadyExists).branchName() == 'gnomish/PROJ-3'
    }

    def "FR7: an unresolvable --base ref is reported as a deterministic result, not a crash"() {
        given:
        def repo = initWorkingRepo(tempDir)
        commit(repo, 'a.txt', 'first')

        when:
        def result = creator.createBranch(repo, 'PROJ-4', 'does-not-resolve')

        then:
        noExceptionThrown()
        result instanceof BranchCreationResult.BaseRefNotResolved
        (result as BranchCreationResult.BaseRefNotResolved).baseRef() == 'does-not-resolve'
    }

    def "FR7: creating the branch does not alter the clone's current branch, HEAD, or working tree"() {
        given:
        def repo = initWorkingRepo(tempDir)
        commit(repo, 'a.txt', 'first')
        def branchBefore = runner.run(repo, 'rev-parse', '--abbrev-ref', 'HEAD').stdout().trim()
        def headBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()

        when:
        creator.createBranch(repo, 'PROJ-5', null)

        then:
        def branchAfter = runner.run(repo, 'rev-parse', '--abbrev-ref', 'HEAD').stdout().trim()
        def headAfter = runner.run(repo, 'rev-parse', 'HEAD').stdout().trim()
        def status = runner.run(repo, 'status', '--porcelain')

        branchAfter == branchBefore
        headAfter == headBefore
        status.stdout().trim().isEmpty()
    }
}
