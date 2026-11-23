package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR19 of migrate-verify-to-executor-contract (design D22, "The resume"): the host reads an
 * interrupted verification through the one recogniser of both media, {@link SnapshotTipCheck},
 * over the task's worktree — the register is the clone's, shared by its worktrees. On the real
 * medium: a registered task's worktree, the production host snapshot step and the production
 * pending write over the run's cell.
 */
class HostInterruptedVerificationReadSpec extends Specification implements HostSnapshotRound {

    @TempDir
    Path tempDir

    def worktrees = new GitTaskWorktrees(runner, ClaimEpochSource.NONE)

    def setup() {
        setupHostRound()
    }

    def "FR19: a register naming the previous round's close names no interrupted verification"() {
        expect:
        worktrees.interruptedVerification(worktree(), TASK).isEmpty()
    }

    def "FR19: a registered host snapshot is an interrupted verification of its round"() {
        given:
        def token = cell.opened()
        runRound(completed()) { gnomeWorks() }
        def snapshot = tip()

        when:
        def found = worktrees.interruptedVerification(worktree(), TASK)

        then:
        found.isPresent()
        found.get().attemptCommit() == snapshot
        found.get().registered() == snapshot
        found.get().stage() == 'implement'
        found.get().round() == ROUND
        found.get().token() == token
        found.get().answer() == new AnswerFileReading.Absent()
    }

    def "FR19: a registered host pending commit is read through to the snapshot below it"() {
        given:
        runRound(completed()) { gnomeWorks() }
        def snapshot = tip()
        persistPending('run-1')

        when:
        def found = worktrees.interruptedVerification(worktree(), TASK)

        then:
        found.get().attemptCommit() == snapshot
        found.get().registered() == tip()
        tip() != snapshot
    }
}
