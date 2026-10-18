package com.github.oinsio.gnomish.gitobjects

import com.github.oinsio.gnomish.subprocess.ProcessLaunch
import com.github.oinsio.gnomish.subprocess.ProcessSupervisor

import com.github.oinsio.gnomish.testfixtures.concurrency.BoundedExecutionFixture
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
        def exec = new GitExec(bare, 'git', new ProcessSupervisor(), ProcessLaunch.grouped())
        def args = [
            'ls-tree',
            '-r',
            'refs/heads/base'
        ]

        // The cap is applied on the drained bytes (design D17 of fix-terminal-receipt-convergence);
        // the zero-cap call stays bounded so a regression to a spinning read fails fast instead of
        // stalling the mutation-testing minion.
        when: 'the same command is run uncapped and capped at zero bytes'
        def uncapped = exec.run(args, null, [:], -1)
        def cappedAtZero = withBoundedWait { exec.run(args, null, [:], 0) }

        then: 'the uncapped read returns the real listing'
        uncapped.exitCode() == 0
        uncapped.stdout().length> 0
        !uncapped.overflowed()

        and: 'the zero-cap read is capped, not treated as uncapped'
        cappedAtZero.exitCode() == 0
        cappedAtZero.stdout().length == 0
        cappedAtZero.overflowed()
    }

    def "FR25: a capped run stops promptly when the calling thread is interrupted"() {
        given: 'a bare repo whose base commit tree is non-empty output for ls-tree'
        def bare = seedBareRepo(tempDir, ['file.txt': 'some content'])
        def exec = new GitExec(bare, 'git', new ProcessSupervisor(), ProcessLaunch.grouped())
        def args = [
            'ls-tree',
            '-r',
            'refs/heads/base'
        ]

        when: 'the calling thread is interrupted before a capped read that would otherwise loop'
        Thread.currentThread().interrupt()
        exec.run(args, null, [:], 1)

        then: 'the run stops at once and says it was interrupted'
        thrown(GitObjectsInterruptedException)

        cleanup:
        Thread.interrupted() // clear the flag so it does not leak into later tests
    }
}
