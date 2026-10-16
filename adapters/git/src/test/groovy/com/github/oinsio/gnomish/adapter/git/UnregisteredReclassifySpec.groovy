package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.branch.BranchShape
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10 of fix-terminal-receipt-convergence (design D13): a branch a plain {@code git clone} brought
 * without its register is {@code Unregistered} on a local read, and the touchpoint fetch — the
 * shape's recovery owner — lands origin's register beside it, after which the branch classifies by
 * the registered commit's content; a second fetch changes nothing.
 */
class UnregisteredReclassifySpec extends Specification implements RegisteredRoundFixture {

    @TempDir
    Path tempDir

    def setup() {
        setupRegisteredRound()
    }

    // Scenario: lifecycle/task-branch-contract / A fetched register reclassifies an Unregistered branch by its content
    def "FR10: the touchpoint fetch lands the register and the Unregistered branch classifies by its content"() {
        given: 'origin holds the branch and its register'
        def bare = initBareRepo(tempDir, 'origin.git')
        addRemote(cloneDir, 'origin', bare.toString())
        assert runner.run(cloneDir, 'push', 'origin', 'HEAD:refs/heads/main').exitCode() == 0
        new BestEffortClosingPush(runner).pushBestEffort(cloneDir, TASK)

        and: 'a peer clone that git clone filled with the task branch as a tracking ref, without its register'
        def peer = tempDir.resolve('peer')
        seedClone(tempDir, bare.toString(), peer)
        def tip = gitOutput(peer, 'rev-parse', 'refs/remotes/origin/gnomish/PROJ-1')

        expect: 'the local read, which fetches nothing, names the half-pair'
        branches.list(peer).find {
            it.shape() instanceof BranchShape.Unregistered
        }?.shape() ==
        new BranchShape.Unregistered('refs/gnomish-factory/PROJ-1', tip)

        when: 'a touchpoint reads the task through the locate'
        def first = branches.classifyShape(peer, TASK)
        def refsAfterFirst = gitOutput(peer, 'for-each-ref')

        then: 'the register landed and the branch is what its registered commit says'
        first == new BranchShape.InProgress()
        registeredId(peer, TASK) == registeredId(cloneDir, TASK)

        when: 'a second touchpoint fetches again'
        def second = branches.classifyShape(peer, TASK)

        then: 'nothing changes'
        second == first
        gitOutput(peer, 'for-each-ref') == refsAfterFirst
    }
}
