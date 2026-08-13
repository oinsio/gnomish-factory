package com.github.oinsio.gnomish.gitobjects

import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR25: failure surfaces of the plumbing commit chain. Every git step is checked — a non-zero exit
 * becomes a loud {@link GitObjectsException} naming the step that failed (never a silent empty
 * result flowing downstream), and a compare-and-swap mismatch against an absent ref reports the
 * ref as absent.
 */
class GitObjectsCommitFailureSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    def "FR25: a commit from a missing parent object fails loudly at read-tree"() {
        given: 'a well-formed object id that names no object in the repository'
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def missingParent = ObjectId.of('a' * 40)

        when:
        git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), missingParent,
                [
                    new TreeEdit.PutFile('f.txt', 'x'.bytes)
                ], metadata()))

        then: 'the failure names read-tree, the first step that could not load the parent tree'
        def e = thrown(GitObjectsException)
        e.message.contains('read-tree')

        and: 'no branch was created'
        git.resolveRef('refs/heads/gnomish/t1').isEmpty()
    }

    def "FR25: a non-commit parent fails loudly at commit-tree"() {
        given: 'a parent id naming a tree object — read-tree accepts it, commit-tree must not'
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def treeId = ObjectId.of(gitOutput(bare, 'rev-parse', 'refs/heads/base^{tree}'))

        when:
        git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), treeId,
                [
                    new TreeEdit.PutFile('f.txt', 'x'.bytes)
                ], metadata()))

        then: 'the failure names commit-tree — its output is checked, not passed along empty'
        def e = thrown(GitObjectsException)
        e.message.contains('commit-tree')

        and: 'no branch was created'
        git.resolveRef('refs/heads/gnomish/t1').isEmpty()
    }

    def "FR25: a PutFile that conflicts with an existing blob fails loudly at update-index"() {
        given: 'a.txt already exists as a blob, so a.txt/nested.txt cannot also be added'
        def bare = seedBareRepo(tempDir, ['a.txt': 'content'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when:
        git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('a.txt/nested.txt', 'x'.bytes)
                ], metadata()))

        then: 'the failure names update-index — its exit code is checked, not ignored'
        def e = thrown(GitObjectsException)
        e.message.contains('update-index')

        and: 'no branch was created'
        git.resolveRef('refs/heads/gnomish/t1').isEmpty()
    }

    def "FR25: deletePath fails loudly when ls-files cannot read a corrupt index"() {
        given: 'a CommitBuilder driven directly so a corrupt index can be injected'
        def bare = seedBareRepo(tempDir, ['a.txt': 'content'])
        def exec = new GitExec(bare, 'git')
        def builder = new CommitBuilder(exec, tempDir)
        def corruptIndex = tempDir.resolve('corrupt.idx')
        Files.writeString(corruptIndex, 'not a real git index')
        def method = CommitBuilder.getDeclaredMethod('deletePath', String, Map, int)
        method.accessible = true
        Object[] args = new Object[3]
        args[0] = 'a.txt'
        args[1] = ['GIT_INDEX_FILE': corruptIndex.toString()]
        args[2] = 40

        when:
        method.invoke(builder, args)

        then: 'the failure names ls-files — its exit code is checked, not ignored'
        def e = thrown(InvocationTargetException)
        e.cause instanceof GitObjectsException
        e.cause.message.contains('ls-files')
    }

    def "FR25: deleting a directory whose entry breaks index-info parsing fails loudly at update-index --index-info"() {
        given: 'a tracked path containing a raw newline, which the index-info format cannot carry'
        def bare = seedBareRepo(tempDir, ['seed.txt': 'seed'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()
        def weirdPath = "d/weird\nname.txt"
        def withFile = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile(weirdPath, 'x'.bytes)
                ], metadata()))

        when: 'a new branch is built off that tip, deleting the directory holding the weird entry'
        git.commit(new CommitRequest('refs/heads/gnomish/t2', Optional.empty(), withFile,
                [
                    new TreeEdit.DeletePath('d')
                ], metadata(1_700_000_100L)))

        then: 'the failure names update-index --index-info — its exit code is checked, not ignored'
        def e = thrown(GitObjectsException)
        e.message.contains('update-index --index-info')

        and: 'no branch was created'
        git.resolveRef('refs/heads/gnomish/t2').isEmpty()
    }

    def "FR25: an expectedTip against an absent ref is refused, reporting the ref as absent"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when: 'the caller expects a tip on a ref that was never created'
        git.commit(new CommitRequest('refs/heads/gnomish/none', Optional.of(base), base,
                [
                    new TreeEdit.PutFile('f.txt', 'x'.bytes)
                ], metadata()))

        then: 'the refusal says the ref is absent rather than misreporting a tip'
        def e = thrown(StaleTipException)
        e.message.contains('absent')
    }
}
