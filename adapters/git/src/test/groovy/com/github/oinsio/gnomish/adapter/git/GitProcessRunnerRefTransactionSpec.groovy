package com.github.oinsio.gnomish.adapter.git

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13 of tighten-spec-contract (design D13, amended 2026-10-13): the runner's typed entry for a ref
 * transaction hands the script to {@code git update-ref --stdin} — the refs land together on a real
 * repository, a refused precondition lands none, and a working directory that does not exist is
 * answered the way every other command's is.
 */
class GitProcessRunnerRefTransactionSpec extends Specification implements BareGitRepoFixture {

    @TempDir
    Path tempDir

    def "FR13: the script reaches git on standard input, and both refs land"() {
        given:
        def repo = initWorkingRepo(tempDir)
        commit(repo, 'a.txt', 'first')
        def head = currentHead(repo)

        when:
        def result = new GitProcessRunner().run(repo, new RefTransaction([
            new RefTransaction.Update('refs/heads/one', head, null),
            new RefTransaction.Update('refs/heads/two', head, null),
        ]))

        then:
        result.exitCode() == 0
        gitOutput(repo, 'rev-parse', 'refs/heads/one') == head
        gitOutput(repo, 'rev-parse', 'refs/heads/two') == head
    }

    def "FR13: a refused compare-and-swap on one ref lands neither, and says so"() {
        given:
        def repo = initWorkingRepo(tempDir)
        commit(repo, 'a.txt', 'first')
        def head = currentHead(repo)

        when:
        def result = new GitProcessRunner().run(repo, new RefTransaction([
            new RefTransaction.Update('refs/heads/one', head, null),
            new RefTransaction.Update('refs/heads/two', head, '0' * 39 + '1'),
        ]))

        then:
        result.exitCode() != 0
        !result.stderr().isBlank()
        gitExitCode(repo, 'rev-parse', '--verify', '--quiet', 'refs/heads/one') != 0
    }

    def "a working directory that does not exist is git's own 128, as for every command"() {
        when:
        def result = new GitProcessRunner().run(tempDir.resolve('missing'), new RefTransaction([
            new RefTransaction.Update('refs/heads/one', '1' * 40, null)
        ]))

        then:
        result.exitCode() == 128
        result.stderr().contains('cwd does not exist')
    }
}
