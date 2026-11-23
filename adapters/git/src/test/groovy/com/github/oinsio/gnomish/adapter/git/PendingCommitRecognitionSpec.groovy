package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.ClosedRound
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR3, FR20 of migrate-verify-to-executor-contract (design D21, "The resume"): a registered pending
 * commit on the real host medium — written by the production host persistence over a snapshot the
 * production host-git decoration took — classifies as the snapshot below it does, is recognised by
 * the one recogniser down to that snapshot, and a pickup that restores it closes the round on the
 * pending commit, the round's last registered commit.
 */
class PendingCommitRecognitionSpec extends Specification implements HostSnapshotRound {

    @TempDir
    Path tempDir

    String snapshot

    def setup() {
        setupHostRound()
        runRound(completed()) { gnomeWorks() }
        snapshot = tip()
    }

    // D21, "Item 6": no shape is added — a pending commit's envelope is the snapshot's position and
    // outcome, so the classifier reads it as the interrupted verification it is
    // Scenario: lifecycle/task-branch-contract / A registered pending commit classifies by its envelope
    def "FR20: a registered pending commit classifies InProgress, the shape of the snapshot below it"() {
        given: 'the shape at the registered snapshot'
        def atSnapshot = shape()

        when:
        persistPending('run-1')
        def atFirst = shape()
        persistPending('run-1', 'run-2')

        then:
        registeredId(cloneDir, TASK) != snapshot
        atSnapshot == new BranchShape.InProgress()
        atFirst == atSnapshot
        shape() == atSnapshot
    }

    def "FR3, FR20: the recogniser follows the host's stacked pending commits down to the snapshot"() {
        given:
        persistPending('run-1')
        persistPending('run-1', 'run-2')

        when:
        def found = new SnapshotTipCheck(runner, cloneDir).inspect(TASK).get()

        then:
        found.attemptCommit() == snapshot
        found.registered() == tip()
        found.stage() == 'implement'
        found.round() == ROUND
        found.token() == cell.opened()
        found.answer() == new AnswerFileReading.Absent()
    }

    // crash-consistency item 14: the pickup rebuilds the round from the record through the live
    // transitions, so its close sits where the live round's would — on the last pending commit
    def "FR20: a pickup that restores the pending commit closes the round on it"() {
        given: 'a round with a pending commit, and a factory that died before the round closed'
        persistPending('run-1')
        def pending = tip()

        and: 'a pickup restores the round the register names into a cell of its own'
        def resumed = new CurrentRound()
        resumed.restore(new SnapshotTipCheck(runner, cloneDir).inspect(TASK).get())

        when:
        def key = new AttemptKey(TASK, 'implement', ROUND)
        new GitAttemptPersistence(runner, worktree(), TASK, ClaimEpochSource.NONE, resumed, new VirtualClock())
                .persist(TASK, RoundStates.recorded(key), new ToolTrace(key, []))

        then:
        resumed.closed() == new ClosedRound(cell.opened(), snapshot, pending, RoundClose.VERIFIABLE)
        registeredId(cloneDir, TASK) == tip()
        gitOutput(cloneDir, 'rev-parse', tip() + '^') == pending
    }
}
