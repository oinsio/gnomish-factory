package com.github.oinsio.gnomish.gitobjects

import java.nio.file.Files
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR25: {@link GitObjects} builds lifecycle commits over bare object storage and advances the
 * branch with an atomic compare-and-swap — no working copy, no checkout. Covers branch creation,
 * fast-forward update, stale-tip refusal (both the "must-not-exist" and "tip moved" cases), and
 * deterministic commit ids.
 */
class GitObjectsCommitSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    def "FR25: creates a task branch on a bare repo with no working copy"() {
        given: 'a bare repo with a base commit and no work tree'
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when: 'a task branch is created carrying task.json'
        def created = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('.gnomish-task/task.json', '{"version":1}'.bytes)
                ], metadata()))

        then: 'the branch points at the new commit and the file is readable as a blob'
        git.resolveRef('refs/heads/gnomish/t1').get() == created
        new String(git.readBlob(created, '.gnomish-task/task.json', 4096), 'UTF-8') == '{"version":1}'

        and: 'nothing was checked out into the bare repo'
        !Files.exists(bare.resolve('.gnomish-task'))
        !Files.exists(bare.resolve('src'))
    }

    def "FR25: a matching expectedTip advances the branch fast-forward"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()
        def first = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('.gnomish-task/task.json', 'v1'.bytes)
                ], metadata()))

        when: 'a second commit names the current tip as its expected value'
        def second = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.of(first), first,
                [
                    new TreeEdit.PutFile('.gnomish-task/task.json', 'v2'.bytes)
                ], metadata(1_700_000_100L)))

        then:
        git.resolveRef('refs/heads/gnomish/t1').get() == second
        new String(git.readBlob(second, '.gnomish-task/task.json', 4096), 'UTF-8') == 'v2'
    }

    def "FR25: a stale expectedTip is refused and leaves the ref unchanged"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()
        def tip = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('.gnomish-task/task.json', 'v1'.bytes)
                ], metadata()))

        when: 'a commit claims the wrong current tip'
        git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.of(base), tip,
                [
                    new TreeEdit.PutFile('.gnomish-task/task.json', 'v2'.bytes)
                ], metadata()))

        then: 'the write is refused and the branch still points at the real tip'
        thrown(StaleTipException)
        git.resolveRef('refs/heads/gnomish/t1').get() == tip
    }

    def "FR25: creating an already-existing ref is refused"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()
        def tip = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('.gnomish-task/task.json', 'v1'.bytes)
                ], metadata()))

        when: 'another creation (empty expectedTip) targets the same, now-existing ref'
        git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('.gnomish-task/task.json', 'other'.bytes)
                ], metadata()))

        then:
        thrown(StaleTipException)
        git.resolveRef('refs/heads/gnomish/t1').get() == tip
    }

    def "FR25: commit ids are deterministic for fixed metadata"() {
        given: 'two branches built from the same parent, edits, and metadata'
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()
        def edits = [
            new TreeEdit.PutFile('.gnomish-task/task.json', 'fixed'.bytes)
        ]

        when:
        def a = git.commit(new CommitRequest('refs/heads/gnomish/a', Optional.empty(), base, edits, metadata()))
        def b = git.commit(new CommitRequest('refs/heads/gnomish/b', Optional.empty(), base, edits, metadata()))

        then: 'the commit object is byte-identical, so the ids match'
        a == b
    }

    def "FR25: commits are stamped with the caller-supplied timestamps in UTC, not the current clock"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when: 'a commit is built from metadata pinned to epoch second 1700000000'
        def created = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('f.txt', 'x'.bytes)
                ], metadata()))

        then: 'the raw commit object carries exactly that instant as "<epoch> +0000" for both identities'
        def raw = gitOutput(bare, 'cat-file', 'commit', created.hex())
        raw.contains('author gnome <gnome@factory> 1700000000 +0000')
        raw.contains('committer gnome <gnome@factory> 1700000000 +0000')
    }
}
