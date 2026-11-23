package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.*
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

/**
 * Task 4.6 of migrate-verify-to-executor-contract (FR10, NFR-R4; design D12 as amended 2026-11-12,
 * D21), the host medium's kill window W3 picked up by a second instance on another clone: the dying
 * instance's poll returned a key it never recorded, so the register names the previous key. The
 * pickup polls that key with no {@code start}; the gate answers {@code pending} under another key,
 * and the rotation lands as the next pending commit — on the recorded one, its register in the same
 * transaction, pushed — before the gate is asked again. The container twin is {@link
 * ContainerPendingRotationSpec}.
 */
class HostPendingRotationSpec extends HostPendingPollSpecBase {

    // Scenario: verification/pending-poll / Kill before the new key lands polls the previous key
    // Scenario: verification/pending-poll / New key lands before the next poll
    // Scenario: git/git-task-persistence / A key rotation stacks a pending commit
    // Scenario: executor/wire-contract / Superseded key still reaches its run
    // FR11 of migrate-verify-to-executor-contract (D11): the dying instance's poll superseded k-previous;
    //     the pickup polls that key, and the gate answers for the same run (pending, under the new key)
    def "W3 on another clone: the previous key is polled, nothing starts, and the key it returns lands on the recorded commit before the next poll"() {
        given: 'the dying instance recorded k-previous, then polled it and died before recording the answer'
        def check = gate(server)
        def world = world([check], 'build')
        def cell = killAfterSnapshot(world.ownGit, world.worktree, TASK, 'build', 0)
        def entries = landEntries(world, cell, 0, [[check, 'k-previous']])
        def recorded = commitOf(world.repoDir, registerRef(TASK))
        script(server, ['RUNNING', 'RUNNING', 'OK'])
        dyingAsk()
        def other = world.secondOnAnotherClone.call(tempDir.resolve('b'))
        observed = other

        when:
        world.pickUpAfterKill()

        then: 'no agent and no start: one rotated pending commit, its only parent the recorded one'
        world.agentRounds.call() == 0
        asked() == 3
        subjectsAbove(other, TASK, recorded).count {
            it.startsWith('gnomish: pending')
        } == 1
        def rotated = closeAbove(other, TASK, recorded)
        subjectOf(other, rotated) == subjectOf(other, recorded)
        gitOutput(other, 'rev-list', '--parents', '-n', '1', rotated).split(' ').drop(1) == [recorded]
        gitOutput(other, 'diff-tree', '-r', '--name-only', '--no-commit-id', recorded, rotated) == '.gnomish-task/state.json'

        and: 'the same entry under the key the poll returned, since unchanged, no outcome named'
        at(other, rotated).pendingPolls()*.key()*.value() == ['pending-when']
        at(other, rotated).pendingPolls()*.subject() == entries*.subject()
        at(other, rotated).pendingPolls()*.since() == entries*.since()
        at(other, rotated).attempts() == at(other, recorded).attempts()

        and: 'the register named the rotated commit before the gate was asked under the new key'
        registersAsked == [recorded, rotated]

        and: 'the round closed on the rotated commit, and origin carries it'
        closedPassing(other, closeAbove(other, TASK, rotated))
        secondPassIsNoOp(world)
    }
}
