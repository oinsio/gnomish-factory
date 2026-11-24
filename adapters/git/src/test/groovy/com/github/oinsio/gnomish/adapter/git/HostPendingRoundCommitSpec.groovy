package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.ClosedRound
import com.github.oinsio.gnomish.domain.engine.PendingSubject
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
        subjectOf(cloneDir, snapshot) == ServiceCommitMessages.pendingSnapshot('implement', ROUND, token)

        and: 'the pending round commit is registered with the branch, on the snapshot alone, changing only state.json'
        def pending = tip()
        pending != snapshot
        registeredId(cloneDir, TASK) == pending
        gitOutput(cloneDir, 'rev-list', '--parents', '-n', '1', pending).split(' ').drop(1).toList() == [snapshot]
        changedIn(pending) == ['.gnomish-task/state.json']
        subjectOf(cloneDir, pending) == ServiceCommitMessages.pending('implement', ROUND, token)

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

        and: 'the cell holds the round open at its snapshot, with the pending commit as its last registered commit'
        cell.snapshot().get() == new ClosedRound(token, snapshot, pending, RoundClose.PENDING)
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
}
