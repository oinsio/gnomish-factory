package com.github.oinsio.gnomish.gitobjects

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of fix-operator-blockers (design D10): the library refuses, when it is opened, a git dir or
 * a temporary-index dir that is not absolute, naming the path — git runs with the git dir as its
 * working directory, so a relative path would be resolved against the wrong directory. Refused,
 * never resolved: the argument owner is the only place a directory becomes absolute.
 */
class GitObjectsAbsolutePathSpec extends Specification implements GitObjectsFixture {

    @TempDir
    Path tempDir

    // FR13: a relative git dir never reaches a git process
    def "FR13: GitExec refuses a relative git dir, naming it"() {
        when:
        new GitExec(Path.of('.git'), 'git')

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'git dir must be an absolute path: .git'
    }

    def "FR13: GitExec accepts an absolute git dir and runs git against it"() {
        given:
        Path bare = seedBareRepo(tempDir, ['file.txt': 'content'])

        when:
        def exec = new GitExec(bare, 'git')

        then:
        exec.gitDir() == bare
        exec.run(['rev-parse', '--git-dir']).exitCode() == 0
    }

    // FR13: a relative temporary-index dir never reaches git as GIT_INDEX_FILE
    def "FR13: CommitBuilder refuses a relative temporary-index dir, naming it"() {
        given:
        def exec = new GitExec(tempDir, 'git')

        when:
        new CommitBuilder(exec, Path.of('tmp'))

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'temporary index directory must be an absolute path: tmp'
    }

    def "FR13: CommitBuilder accepts an absolute temporary-index dir"() {
        when:
        new CommitBuilder(new GitExec(tempDir, 'git'), tempDir.resolve('index'))

        then:
        noExceptionThrown()
    }

    // FR13: the public entry point carries both refusals
    def "FR13: GitObjects.open refuses a relative #which"() {
        when:
        GitObjects.open(gitDir.call(tempDir), indexDir.call(tempDir))

        then:
        def e = thrown(IllegalArgumentException)
        e.message.endsWith(named)

        where:
        which | gitDir | indexDir | named
        'git dir' | { Path t ->
            Path.of('.git')
        } | { Path t ->
            t
        } | ': .git'
        'temporary-index dir' | { Path t ->
            t
        } | { Path t ->
            Path.of('tmp')
        } | ': tmp'
    }
}
