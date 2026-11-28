package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.ClosedRound
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.BranchShape
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10 of extract-agent-round-runner (design D7, D11): the host end of the pending round commit on
 * the real medium — the production host-git decoration snapshots a {@code transform} round that
 * answered {@code pending} under the pending-round subject, and the production persistence lands the
 * round's own entry above that snapshot as a pending commit: exactly {@code state.json}, the snapshot
 * its only parent, its register in the same ref transaction.
 */
class HostPendingRoundCommitSpec extends Specification implements HostSnapshotRound {

    @TempDir
    Path tempDir

    def setup() {
        setupHostRound()
    }

    // Scenario: pipeline/stage-engine / Pending round commit lands before the first poll
    def "FR10: a pending host round's working copy and its round entry land in a pending round commit on its pending-round snapshot"() {
        given:
        def token = cell.opened()
        def answer = pending('job-7')

        when: 'the round answers pending, and the engine writes its entry before any poll'
        def result = runRound(answer) { gnomeWorks() }
        def snapshot = tip()
        persistPendingRound('job-7')

        then: 'the answer is the round\'s, and its snapshot carries the pending-round subject'
        result.is(answer)
        subjectOf(cloneDir, snapshot) == RoundSubject.pendingSnapshot('implement', ROUND, token)

        and: 'the pending round commit is registered with the branch, on the snapshot alone, changing only state.json'
        def pending = tip()
        pending != snapshot
        registeredId(cloneDir, TASK) == pending
        gitOutput(cloneDir, 'rev-list', '--parents', '-n', '1', pending).split(' ').drop(1).toList() == [snapshot]
        changedIn(pending) == ['.gnomish-task/state.json']
        subjectOf(cloneDir, pending) == RoundSubject.pending('implement', ROUND, token)

        and: 'its tree carries the round\'s working copy, and its state the round\'s own entry'
        fileAt(pending, 'App.java') == 'class App {}'
        fileAt(pending, 'notes.md') == 'left on disk'
        def entries = StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        UntrustedText.branchDocument(fileAt(pending, '.gnomish-task/state.json')))).pendingPolls()
        entries.size() == 1
        entries.first().subject() == PendingSubject.ROUND
        entries.first().token() == token
        entries.first().attempt() == ROUND
        entries.first().key() == new PendingKey('job-7')

        and: 'the worktree no longer lags it: its index and working tree carry its state.json (D11, F1)'
        def blob = gitOutput(cloneDir, 'rev-parse', pending + ':.gnomish-task/state.json')
        gitOutput(worktree(), 'rev-parse', ':.gnomish-task/state.json') == blob
        gitOutput(worktree(), 'hash-object', '--no-filters', '--', '.gnomish-task/state.json') == blob
        gitOutput(worktree(), 'status', '--porcelain') == ''

        and: 'the cell holds the round open at its snapshot, with the pending commit as its last registered commit'
        cell.snapshot().get() == new ClosedRound(token, snapshot, pending, RoundClose.PENDING)
    }

    // FR11, NFR-R3 of extract-agent-round-runner (D11, "Resume"): a registered pending round commit adds
    //     no shape — it classifies as the pending-round snapshot below it does — and the one recogniser
    //     reads it as a pending round of the round's token, never as an interrupted verification
    // Scenario: lifecycle/task-branch-contract / A pending round is resumed by the same owner
    def "FR11: a registered pending round commit classifies as its pending-round snapshot does and is recognised as a pending round"() {
        given:
        def token = cell.opened()
        runRound(pending('job-7')) { gnomeWorks() }
        def snapshot = tip()
        def atSnapshot = shape()

        when:
        persistPendingRound('job-7')
        def found = new SnapshotTipCheck(runner, cloneDir).inspect(TASK).get()

        then: 'the shape is InProgress, the shape of the pending-round snapshot below'
        atSnapshot == new BranchShape.InProgress()
        shape() == atSnapshot

        and: 'the recogniser names a pending round of the round\'s token, its chain from the register down'
        found.close() == RoundClose.PENDING
        found.token() == token
        found.attemptCommit() == snapshot
        found.chain() == [tip(), snapshot]
    }

    // FR10, D11 of extract-agent-round-runner: the host chain forwards a poll with its turn, and a
    //     terminal poll's round takes the verifiable snapshot a start's would (task 6.4b keeps the
    //     snapshot step as it was; 6.4c/6.4d move a poll onto the open round)
    def "FR10: a poll goes down the host chain with its turn, and a completed poll is snapshotted verifiable"() {
        given:
        def token = cell.opened()
        def answer = completed()

        when:
        def result = pollRound(answer, new PendingTurn(new PendingKey('job-7'), PendingTurn.Origin.ISSUED)) {
            gnomeWorks()
        }

        then:
        result.is(answer)
        subjectOf(cloneDir, tip()) == RoundSubject.snapshot('implement', ROUND, token)
        cell.closed().close() == RoundClose.VERIFIABLE
    }

    // FR10 (design D11, window K1): a registered pending-round snapshot is never a round to verify
    def "FR10: a register naming a pending-round snapshot is not read as an interrupted verification"() {
        when:
        runRound(pending('job-7')) { gnomeWorks() }

        then:
        registeredId(cloneDir, TASK) == tip()
        new SnapshotTipCheck(runner, cloneDir).inspect(TASK).isEmpty()
    }

    // FR10 (design D11): the round's close drops the entry; it lands on the pending round commit
    def "FR10: the close of a pending host round lands on its pending round commit and carries no pending entry"() {
        given:
        runRound(pending('job-7')) { gnomeWorks() }
        persistPendingRound('job-7')
        def pending = tip()

        when:
        persistRound()

        then:
        def round = tip()
        registeredId(cloneDir, TASK) == round
        gitOutput(cloneDir, 'rev-parse', round + '^') == pending
        !fileAt(round, '.gnomish-task/state.json').contains('job-7')
    }

    // Scenario: git/git-task-persistence / The closing snapshot sits on the last pending round commit
    def "FR10: the terminal poll of a pending host round snapshots it on its last pending round commit under the round's token"() {
        given: 'a round that went pending, with its pending round commit registered'
        def token = cell.opened()
        runRound(pending('job-7')) { gnomeWorks() }
        def pendingSnapshot = tip()
        persistPendingRound('job-7')
        def pending = tip()

        when: 'the terminal poll answers completed after a poll that changed nothing'
        pollRound(completed(), new PendingTurn(new PendingKey('job-7'), PendingTurn.Origin.ISSUED)) {}

        then: 'the closing snapshot is registered with the branch, its only parent the pending round commit'
        def snapshot = tip()
        snapshot != pending
        registeredId(cloneDir, TASK) == snapshot
        gitOutput(cloneDir, 'rev-list', '--parents', '-n', '1', snapshot).split(' ').drop(1).toList() == [pending]

        and: 'its subject names the token of the round\'s pending-round snapshot'
        subjectOf(cloneDir, pendingSnapshot) == RoundSubject.pendingSnapshot('implement', ROUND, token)
        subjectOf(cloneDir, snapshot) == RoundSubject.snapshot('implement', ROUND, token)
        cell.closed() == new ClosedRound(token, snapshot, snapshot, RoundClose.VERIFIABLE)

        and: 'it carries the record the pending round commit carried: the lag was settled, not staged'
        fileAt(snapshot, '.gnomish-task/state.json') == fileAt(pending, '.gnomish-task/state.json')
        changedIn(snapshot).isEmpty()

        when: 'the round closes'
        persistRound()

        then: 'its round commit sits on the closing snapshot'
        gitOutput(cloneDir, 'rev-parse', tip() + '^') == snapshot
    }

    // Scenario: git/git-task-persistence / The factory's own commits of a pending round are no gnome commits
    def "FR10: a pending host round whose executor committed during a poll is judged from its last pending round commit"() {
        given:
        def token = cell.opened()
        runRound(pending('job-7')) { gnomeWorks() }
        def pendingSnapshot = tip()
        persistPendingRound('job-7')
        def pending = tip()

        when: 'the executor commits in the working copy during the terminal poll'
        pollRound(completed(), new PendingTurn(new PendingKey('job-7'), PendingTurn.Origin.ISSUED)) {
            commit(worktree(), 'Polled.java', 'class Polled {}')
        }

        then: 'the snapshot was not declined: the range it was judged over is the executor\'s commit alone'
        def snapshot = tip()
        subjectOf(cloneDir, snapshot) == RoundSubject.snapshot('implement', ROUND, token)
        gitOutput(cloneDir, 'rev-list', "${pending}..${snapshot}^").readLines() == [
            gitOutput(cloneDir, 'rev-parse', snapshot + '^')
        ]
        subjectOf(cloneDir, snapshot + '^') == 'Polled.java'
        gitOutput(cloneDir, 'rev-parse', snapshot + '^^') == pending
        gitOutput(cloneDir, 'rev-parse', pending + '^') == pendingSnapshot

        and: 'the worktree\'s state.json lag is no boundary violation at the round close'
        persistRound()
        gitOutput(cloneDir, 'rev-parse', tip() + '^') == snapshot
    }

    // FR10 (design D11 as amended 2026-11-25): the host medium settles the lag through the one
    //     PendingStateLag over the worktree, and hands back a restore it could not make
    def "FR10: the worktree medium reports a lag it recognised and could not restore"() {
        given: 'a chain whose last registered commit cannot be read, above the snapshot the index carries'
        runRound(pending('job-7')) { gnomeWorks() }
        def order = new SnapshotOrder(new AttemptKey(TASK, 'implement', ROUND), cell.opened(), 'unused', ['f' * 40, tip()])

        expect:
        new WorktreeSnapshot(runner, worktree(), TASK, ClaimEpochSource.NONE).settle(order).isPresent()
    }
}
