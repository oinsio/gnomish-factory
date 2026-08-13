package com.github.oinsio.gnomish.gitobjects

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir
/**
 * FR25/FR17: reading through {@link GitObjects} — byte round-trips, a size cap that refuses rather
 * than truncates, absent-object and ref-resolution handling — plus the path validation that keeps
 * absolute, {@code ..}, and {@code .git} paths out of every edit and read.
 */
class GitObjectsReadSpec extends Specification implements GitObjectsFixture, BoundedExecutionFixture {

    @TempDir
    Path tempDir

    def "FR25: blob content round-trips byte for byte"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()
        byte[] payload = [0, 10, 13, 65, 66, 67, -1] as byte[]

        when: 'a commit writes arbitrary bytes and they are read back'
        def tip = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('blob.bin', payload)
                ], metadata()))

        then:
        Arrays.equals(git.readBlob(tip, 'blob.bin', 1024), payload)
    }

    def "FR25: readBlob rejects a non-positive size cap before touching git (#cap)"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when:
        git.readBlob(base, 'src/App.java', cap)

        then:
        thrown(IllegalArgumentException)

        where:
        cap << [-1L, 0L]
    }

    def "FR25: readBlob at the exact size cap returns the full content, not truncated (cap=#cap)"() {
        given: 'a blob whose content is exactly 20 bytes'
        def content = 'x' * 20
        def bare = seedBareRepo(tempDir, ['big.txt': content])
        def git = openGitObjects(bare, tempDir)
        def tip = git.resolveRef('refs/heads/base').get()

        // cap=20 drains readCapped's `remaining` to exactly zero — a mutant of its "remaining > 0"
        // boundary would busy-spin forever there with no blocking I/O to interrupt, so the call is
        // bounded, turning a hang into a fast, clean failure instead of stalling the
        // mutation-testing minion.
        expect: 'a cap exactly at or above the content length reads it whole'
        new String(withBoundedWait {
            git.readBlob(tip, 'big.txt', cap)
        }, 'UTF-8') == content

        where:
        cap << [20L, 21L]
    }

    def "FR25: readBlob refuses content that exceeds the cap by exactly one byte"() {
        given: 'a blob whose content is exactly 20 bytes and a cap one byte too small'
        def content = 'x' * 20
        def bare = seedBareRepo(tempDir, ['big.txt': content])
        def git = openGitObjects(bare, tempDir)
        def tip = git.resolveRef('refs/heads/base').get()

        when:
        git.readBlob(tip, 'big.txt', 19)

        then:
        thrown(BlobTooLargeException)
    }

    def "FR25: a blob over the size cap is refused, never truncated"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()
        def tip = git.commit(new CommitRequest('refs/heads/gnomish/t1', Optional.empty(), base,
                [
                    new TreeEdit.PutFile('big.txt', ('x' * 5000).bytes)
                ], metadata()))

        when:
        git.readBlob(tip, 'big.txt', 100)

        then:
        thrown(BlobTooLargeException)
    }

    def "FR25: reading a path absent from the commit throws MissingObject"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when:
        git.readBlob(base, 'nope/missing.json', 1024)

        then:
        thrown(MissingObjectException)
    }

    def "FR25: resolveRef is empty for a ref that does not exist"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)

        expect:
        git.resolveRef('refs/heads/gnomish/absent').isEmpty()
        git.resolveRef('refs/heads/base').isPresent()
    }

    def "FR25/FR17: edit paths that escape the working tree are refused at construction (#path)"() {
        when:
        new TreeEdit.PutFile(path, 'x'.bytes)

        then:
        thrown(InvalidTreePathException)

        where:
        path << [
            '/etc/passwd',
            '../escape',
            'a/../b',
            '.git/config',
            'a/.git/hooks/pre-commit',
            'a//b',
            ''
        ]
    }

    def "FR25/FR17: readBlob rejects an unsafe path before touching git (#path)"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when:
        git.readBlob(base, path, 1024)

        then:
        thrown(InvalidTreePathException)

        where:
        path << ['/abs', '../x', '.git/config']
    }
    def "FR21: exists answers blob and tree presence from bare objects (#path -> #expected)"() {
        given:
        def bare = seedBareRepo(tempDir, ['src/App.java': 'class App {}', 'README.md': 'readme'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        expect: 'existence is answered from the commit tree, blob or tree alike, no checkout'
        git.exists(base, path) == expected

        where:
        path | expected
        'README.md' | true
        'src/App.java' | true
        'src' | true
        'absent.md' | false
        'src/Nope.java'| false
    }

    def "FR21/FR17: exists rejects an unsafe path before touching git (#path)"() {
        given:
        def bare = seedBareRepo(tempDir, ['README.md': 'readme'])
        def git = openGitObjects(bare, tempDir)
        def base = git.resolveRef('refs/heads/base').get()

        when:
        git.exists(base, path)

        then:
        thrown(InvalidTreePathException)

        where:
        path << [
            '/etc/passwd',
            '../outside',
            '.git/config',
            ''
        ]
    }
}
