package com.github.oinsio.gnomish.app.killpoint

import static com.github.oinsio.gnomish.app.HostVerificationKill.*
import static com.github.oinsio.gnomish.app.killpoint.PendingVerificationKill.*

/**
 * Task 4.6 of migrate-verify-to-executor-contract (FR10, NFR-R4; design D12 as amended 2026-11-12,
 * D21), the container medium's kill window W3 on the clone that wrote it, without a daemon ({@link
 * ContainerPendingKill}): the dying instance's poll returned a key it never recorded, so the register
 * names the previous key. The pickup polls that key with no {@code start}; the gate answers {@code
 * pending} under another key, and the rotation lands as the next pending commit — in the box, on the
 * recorded one, admitted with its register and pushed — before the gate is asked again. The host twin
 * is {@link HostPendingRotationSpec}.
 */
class ContainerPendingRotationSpec extends ContainerPendingPollSpecBase {

    // Scenario: verification/pending-poll / Kill before the new key lands polls the previous key
    // Scenario: verification/pending-poll / New key lands before the next poll
    // Scenario: git/git-task-persistence / A key rotation stacks a pending commit
    def "W3: the previous key is polled, nothing starts, and the key it returns lands on the recorded commit before the next poll"() {
        given: 'the dying instance recorded k-previous, then polled it and died before recording the answer'
        def check = gate(server)
        def world = world([check], 'build')
        def cell = kill.killAfterSnapshot('build', 0)
        def entries = kill.landEntries(cell, 0, [[check, 'k-previous']])
        def recorded = commitOf(world.repoDir, registerRef(TASK))
        script(server, ['RUNNING', 'RUNNING', 'OK'])
        dyingAsk()
        observed = world.repoDir

        when:
        kill.pickUp()

        then: 'no agent and no start: one rotated pending commit, its only parent the recorded one'
        kill.agentRounds == 0
        asked() == 3
        subjectsAbove(world.repoDir, TASK, recorded).count {
            it.startsWith('gnomish: pending')
        } == 1
        def rotated = closeAbove(world.repoDir, TASK, recorded)
        subjectOf(world.repoDir, rotated) == subjectOf(world.repoDir, recorded)
        gitOutput(world.repoDir, 'rev-list', '--parents', '-n', '1', rotated).split(' ').drop(1) == [recorded]
        gitOutput(world.repoDir, 'diff-tree', '-r', '--name-only', '--no-commit-id', recorded, rotated) ==
                '.gnomish-task/state.json'

        and: 'the same entry under the key the poll returned, since unchanged, no outcome named'
        at(world.repoDir, rotated).pendingPolls()*.key()*.value() == ['pending-when']
        at(world.repoDir, rotated).pendingPolls()*.check() == entries*.check()
        at(world.repoDir, rotated).pendingPolls()*.since() == entries*.since()
        at(world.repoDir, rotated).attempts() == at(world.repoDir, recorded).attempts()

        and: 'the register named the rotated commit before the gate was asked under the new key'
        registersAsked == [recorded, rotated]

        and: 'the round closed on the rotated commit'
        closedPassing(world.repoDir, closeAbove(world.repoDir, TASK, rotated))
        secondPassIsNoOp(world)
    }
}
