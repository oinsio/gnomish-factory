package com.github.oinsio.gnomish.gitobjects

import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR25: {@link GitExec#run}'s stdout size cap — the exact boundary between "uncapped" ({@code
 * stdoutCap < 0}) and "capped at zero bytes" ({@code stdoutCap == 0}), which {@link GitObjects}
 * never exercises directly since it requires a positive cap.
 */
class GitExecReadCappedSpec extends Specification implements GitObjectsFixture, BoundedExecutionFixture {

    @TempDir
    Path tempDir

    def "FR25: a stdout cap of exactly zero captures nothing and reports truncation, unlike an uncapped read"() {
        given: 'a bare repo whose base commit tree is non-empty output for ls-tree'
        def bare = seedBareRepo(tempDir, ['file.txt': 'some content'])
        def exec = new GitExec(bare, 'git')
        def args = [
            'ls-tree',
            '-r',
            'refs/heads/base'
        ]

        // A mutant of readCapped's "remaining > 0" boundary would busy-spin forever once
        // `remaining` reaches exactly zero — no blocking I/O to interrupt — so the zero-cap call
        // is bounded, turning a hang into a fast, clean failure instead of stalling the
        // mutation-testing minion.
        when: 'the same command is run uncapped and capped at zero bytes'
        def uncapped = exec.run(args, null, [:], -1)
        def cappedAtZero = withBoundedWait { exec.run(args, null, [:], 0) }

        then: 'the uncapped read returns the real listing'
        uncapped.exitCode() == 0
        uncapped.stdout().length> 0
        !uncapped.truncated()

        and: 'the zero-cap read is capped, not treated as uncapped'
        cappedAtZero.exitCode() == 0
        cappedAtZero.stdout().length == 0
        cappedAtZero.truncated()
    }

    def "FR25: readCapped stops promptly instead of spinning when the calling thread is interrupted"() {
        given: 'a bare repo whose base commit tree is non-empty output for ls-tree'
        def bare = seedBareRepo(tempDir, ['file.txt': 'some content'])
        def exec = new GitExec(bare, 'git')
        def args = [
            'ls-tree',
            '-r',
            'refs/heads/base'
        ]

        when: 'the calling thread is interrupted before a capped read that would otherwise loop'
        Thread.currentThread().interrupt()
        exec.run(args, null, [:], 1)

        then: 'the read stops immediately rather than looping — the same interruption check that keeps a boundary-mutated remaining-bytes loop from busy-spinning forever'
        thrown(GitObjectsException)

        cleanup:
        Thread.interrupted() // clear the flag so it does not leak into later tests
    }
}
