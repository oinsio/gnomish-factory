package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gitobjects.ObjectId
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR2, FR7 of add-git-workflow (design D7); FR15 of add-base-ref-resolution: branch creation from
 * the caller's already-peeled law commit, never fetching, pulling, or resolving a base name. The
 * caller peels exactly once and hands the commit down, so this class has no name to look up.
 */
class TaskBranchCreatorSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def runner = new GitProcessRunner()
    def creator = new TaskBranchCreator(runner)

    private String commitAndGetSha(Path repo, String fileName, String content) {
        new File(repo.toFile(), fileName).text = content
        runner.run(repo, 'add', fileName)
        runner.run(repo, '-c', 'user.email=a@b.c', '-c', 'user.name=a', 'commit', '-m', fileName)
        runner.run(repo, 'rev-parse', 'HEAD').stdout().forParsing().trim()
    }

    def "FR7: the branch is created at the commit it was handed"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def head = commitAndGetSha(repo, 'a.txt', 'first')

        when:
        def result = creator.createBranch(repo, 'PROJ-1', ObjectId.of(head))

        then:
        result instanceof BranchCreationResult.Created
        (result as BranchCreationResult.Created).baseCommit() == head
    }

    // FR15: the start point is a commit, so an older one is used verbatim — no ref name is
    //     consulted, and nothing about the clone's current position enters the answer.
    def "FR15: an older commit is branched from verbatim, not the clone's HEAD"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def older = commitAndGetSha(repo, 'a.txt', 'first')
        commitAndGetSha(repo, 'b.txt', 'second')

        when:
        def result = creator.createBranch(repo, 'PROJ-2', ObjectId.of(older))

        then:
        (result as BranchCreationResult.Created).baseCommit() == older
        runner.run(repo, 'rev-parse', 'gnomish/PROJ-2').stdout().forParsing().trim() == older
    }

    // FR15, NFR-S1: a local branch and a local tag both carrying the base's name are exactly what
    //     git's bare-name lookup would answer with. The commit input cannot see either of them.
    def "FR15: a local branch and a local tag under the base's name cannot redirect the start point"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def intended = commitAndGetSha(repo, 'a.txt', 'first')
        def decoy = commitAndGetSha(repo, 'b.txt', 'second')
        runner.run(repo, 'branch', 'release', decoy)
        runner.run(repo, 'tag', 'release', decoy)

        when:
        def result = creator.createBranch(repo, 'PROJ-6', ObjectId.of(intended))

        then:
        (result as BranchCreationResult.Created).baseCommit() == intended
        runner.run(repo, 'rev-parse', 'gnomish/PROJ-6').stdout().forParsing().trim() == intended
    }

    def "FR2: branch name is sanitized via TaskIdSanitizer"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def head = commitAndGetSha(repo, 'a.txt', 'first')

        when:
        creator.createBranch(repo, 'PROJ 42: fix/it', ObjectId.of(head))

        then:
        def listed = runner.run(repo, 'branch', '--list', 'gnomish/PROJ-42-fix-it')
        listed.stdout().forParsing().contains('gnomish/PROJ-42-fix-it')
    }

    def "FR7: branch-already-exists is reported as a deterministic result, not a crash"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def head = commitAndGetSha(repo, 'a.txt', 'first')
        creator.createBranch(repo, 'PROJ-3', ObjectId.of(head))

        when:
        def result = creator.createBranch(repo, 'PROJ-3', ObjectId.of(head))

        then:
        noExceptionThrown()
        result instanceof BranchCreationResult.AlreadyExists
        (result as BranchCreationResult.AlreadyExists).branchName() == 'gnomish/PROJ-3'
    }

    // FR15: the clone was changed underneath the run — the object the caller peeled is simply not
    //     here. A deterministic result, never a fetch and never a fall back to a name.
    def "FR15: a start-point commit the clone does not hold is reported as a deterministic result"() {
        given:
        def repo = initWorkingRepo(tempDir)
        commitAndGetSha(repo, 'a.txt', 'first')
        def absent = ObjectId.of('0123456789abcdef0123456789abcdef01234567')

        when:
        def result = creator.createBranch(repo, 'PROJ-4', absent)

        then:
        noExceptionThrown()
        result instanceof BranchCreationResult.BaseCommitMissing
        (result as BranchCreationResult.BaseCommitMissing).baseCommit() == absent.hex()
    }

    // FR15: a tree object is a well-formed object name that is not a commit — the peel type alone
    //     cannot rule it out, so the existence check asks for a commit specifically.
    def "FR15: an object that is not a commit is refused rather than branched from"() {
        given:
        def repo = initWorkingRepo(tempDir)
        commitAndGetSha(repo, 'a.txt', 'first')
        def tree = ObjectId.of(runner.run(repo, 'rev-parse', 'HEAD^{tree}').stdout().forParsing().trim())

        when:
        def result = creator.createBranch(repo, 'PROJ-7', tree)

        then:
        result instanceof BranchCreationResult.BaseCommitMissing
    }

    def "FR7: creating the branch does not alter the clone's current branch, HEAD, or working tree"() {
        given:
        def repo = initWorkingRepo(tempDir)
        def head = commitAndGetSha(repo, 'a.txt', 'first')
        def branchBefore = runner.run(repo, 'rev-parse', '--abbrev-ref', 'HEAD').stdout().forParsing().trim()
        def headBefore = runner.run(repo, 'rev-parse', 'HEAD').stdout().forParsing().trim()

        when:
        creator.createBranch(repo, 'PROJ-5', ObjectId.of(head))

        then:
        def branchAfter = runner.run(repo, 'rev-parse', '--abbrev-ref', 'HEAD').stdout().forParsing().trim()
        def headAfter = runner.run(repo, 'rev-parse', 'HEAD').stdout().forParsing().trim()
        def status = runner.run(repo, 'status', '--porcelain')

        branchAfter == branchBefore
        headAfter == headBefore
        status.stdout().forParsing().trim().isEmpty()
    }
}
