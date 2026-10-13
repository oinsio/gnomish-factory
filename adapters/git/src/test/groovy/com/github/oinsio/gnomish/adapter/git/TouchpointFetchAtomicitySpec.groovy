package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.gittransfer.Refspec
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR13, NFR-S2 of tighten-spec-contract (design D13 as amended 2026-10-13), on real git and a bare
 * origin: the touchpoint fetch's branch refspec is unforced, so the clone rejects a branch origin
 * rewrote off the line its tracking ref holds — and because the fetch is atomic, the forced
 * register beside it does not land either. Without {@code --atomic} git would still write the
 * register, leaving a register newer than the branch the clone holds.
 */
class TouchpointFetchAtomicitySpec extends Specification implements BareGitRepoFixture {

    private static final String TASK = 'T-1'
    private static final String BRANCH = 'gnomish/T-1'
    private static final String TRACKING = 'refs/remotes/origin/gnomish/T-1'
    private static final String REGISTER = 'refs/gnomish-factory/T-1'

    @TempDir
    Path tempDir

    // Scenario: git/git-task-persistence / A rejected branch update leaves the register unmoved
    def "FR13: a rejected branch update leaves the tracking ref and the register where they were"() {
        given: 'origin holds the branch and its register at one commit, and the clone fetched both'
        def runner = new GitProcessRunner()
        def writer = initTaskWorkingRepo(tempDir, TASK)
        def origin = addConvergedOrigin(writer, tempDir)
        def first = currentHead(writer)
        assert runner.run(writer, 'push', 'origin', "${first}:${REGISTER}").exitCode() == 0
        def clone = tempDir.resolve('clone')
        seedClone(tempDir, origin.toString(), clone, '--branch', BRANCH, '--single-branch')
        def touchpoint = new TouchpointFetch(runner)
        def branchRefspec = new Refspec("${BRANCH}:${TRACKING}")
        assert touchpoint.fetch(clone, TASK, branchRefspec, FactoryRegister.fetchedInPlace(TASK)).exitCode() == 0
        assert gitOutput(clone, 'rev-parse', TRACKING) == first
        assert gitOutput(clone, 'rev-parse', REGISTER) == first

        and: 'origin rewrites the branch off that line and moves the register to the rewrite'
        gitOutput(writer, 'checkout', '-q', '--orphan', 'rewrite')
        commit(writer, 'b.txt', 'rewritten')
        def rewrite = currentHead(writer)
        assert runner.run(writer, 'push', '--force', 'origin', "${rewrite}:refs/heads/${BRANCH}").exitCode() == 0
        assert runner.run(writer, 'push', '--force', 'origin', "${rewrite}:${REGISTER}").exitCode() == 0

        when:
        def result = touchpoint.fetch(clone, TASK, branchRefspec, FactoryRegister.fetchedInPlace(TASK))

        then: 'the fetch reports the rejection'
        result.exitCode() != 0
        result.stderr().contains('rejected')

        and: 'neither ref moved'
        gitOutput(clone, 'rev-parse', TRACKING) == first
        gitOutput(clone, 'rev-parse', REGISTER) == first
    }
}
