package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.git.BranchLocation
import com.github.oinsio.gnomish.app.port.git.BranchLocationUnavailableException
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR11 of fix-terminal-receipt-convergence (design D13 as amended for task 6.6): {@code
 * GitTaskBranches}' classification over a location the caller's own locate answered — the same
 * classification the two-argument read gives, with no second lookup, so a caller that locates once
 * pays one touchpoint fetch, and every location the locate can answer has one parse.
 */
class LocatedShapeReadSpec extends Specification implements RegisteredRoundFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupRegisteredRound()
    }

    // FR11: over a local head the read is the two-argument read's answer
    def "FR11: the read over a located local head names the tip at its register, as the two-argument read does"() {
        when:
        def location = branches.locate(cloneDir, TASK)

        then:
        location instanceof BranchLocation.Local
        branches.classifyShape(cloneDir, TASK, location) == new BranchShape.InProgress()
        branches.classifyShape(cloneDir, TASK) == new BranchShape.InProgress()
    }

    // FR11, FR6, FR5 of own-git-transfer-argv: the three answers that are not a branch
    def "FR11: no branch is Bare, a refused one Corrupt with the report, an unanswered origin is thrown"() {
        expect:
        branches.classifyShape(cloneDir, TASK, new BranchLocation.NotFound()) == new BranchShape.Bare()
        branches.classifyShape(cloneDir, TASK, new BranchLocation.Refused(UntrustedText.subprocess('bad object'))) ==
                new BranchShape.Corrupt('bad object')

        when:
        branches.classifyShape(cloneDir, TASK, new BranchLocation.Unavailable(UntrustedText.subprocess('refused')))

        then:
        def outage = thrown(BranchLocationUnavailableException)
        outage.reason().forLog() == 'refused'
    }

    // FR11, NFR-C2: a branch a plain clone brought without its register is located once — the
    //     touchpoint fetch lands the register in place — and classified over that same location,
    //     by the registered commit's content; the read itself fetches nothing more
    // Scenario: git/git-task-persistence / A third party's fetch brings the branch without its register
    def "FR11: an Unregistered tracking-only branch, located once, classifies by its content over that location"() {
        given: 'origin holds the branch and its register'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        assert runner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main').exitCode() == 0
        new BestEffortClosingPush(runner).pushBestEffort(cloneDir, TASK)

        and: 'a peer clone git clone filled with the task branch as a tracking ref, without its register'
        def peer = tempDir.resolve('peer')
        seedClone(tempDir, bare.toString(), peer)
        assert branches.list(peer)*.shape().any {
            it instanceof BranchShape.Unregistered
        }

        when:
        def location = branches.locate(peer, TASK)
        def refsAfterLocate = gitOutput(peer, 'for-each-ref')
        def shape = branches.classifyShape(peer, TASK, location)

        then:
        location instanceof BranchLocation.RemoteTracking
        shape == new BranchShape.InProgress()
        registeredId(peer, TASK) == registeredId(cloneDir, TASK)
        gitOutput(peer, 'for-each-ref') == refsAfterLocate
    }
}
