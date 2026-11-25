package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.adapter.git.state.StateJsonMapper
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.ClosedRound
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.ToolTrace
import com.github.oinsio.gnomish.domain.engine.fake.RoundStates
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.nio.file.Path
import spock.lang.Specification
import spock.lang.TempDir

/**
 * FR10 of extract-agent-round-runner (design D7, D11): the container end of the pending round commit
 * on local repositories with container semantics ({@link LocalBoxEnvironment}) — the box's round
 * close under the pending-round subject, harvested and admitted, and the production persistence
 * landing the round's own entry above it as a pending commit: exactly {@code state.json}, the
 * snapshot its only parent, its register in the same admission.
 */
class InBoxPendingRoundCommitSpec extends Specification implements InBoxPendingRound {

    @TempDir
    Path tempDir

    def setup() {
        setupPendingRound(RoundClose.PENDING)
    }

    // Scenario: pipeline/stage-engine / Pending round commit lands before the first poll
    def "FR10: a pending box round's working copy and its round entry land in a pending round commit on its pending-round snapshot"() {
        given:
        def token = rounds.opened()

        when:
        persistence().persistPending(TASK, roundPendingState('job-7'))

        then: 'the round\'s close is registered under the pending-round subject, carrying the gnome\'s work'
        gitOutput(cloneDir, 'log', '-1', '--format=%s', snapshot) == ServiceCommitMessages.pendingSnapshot('implement', 1, token)
        gitOutput(cloneDir, 'show', snapshot + ':work.txt') == 'gnome work'

        and: 'the pending round commit is registered on the snapshot alone, changing only state.json'
        def pending = tip()
        registered() == pending
        parentsOf(pending) == snapshot
        changedIn(pending) == [STATE]
        gitOutput(cloneDir, 'log', '-1', '--format=%s', pending) == ServiceCommitMessages.pending('implement', 1, token)
        gitOutput(cloneDir, 'show', pending + ':work.txt') == 'gnome work'

        and: 'its state carries the round\'s own entry'
        def entries = StateJsonMapper.fromDto(StateJsonMapper.readDto(
                        UntrustedText.branchDocument(gitOutput(cloneDir, 'show', pending + ':' + STATE)))).pendingPolls()
        entries.size() == 1
        entries.first().subject() == PendingSubject.ROUND
        entries.first().token() == token
        entries.first().key() == new PendingKey('job-7')

        and: 'the box\'s working copy no longer lags it: its index and working tree carry its state.json (D11, F1)'
        gitOutput(box.workingCopy, 'rev-parse', 'HEAD') == pending
        gitOutput(box.workingCopy, 'rev-parse', ':' + STATE) == gitOutput(cloneDir, 'rev-parse', pending + ':' + STATE)
        gitOutput(box.workingCopy, 'hash-object', '--no-filters', '--', STATE) == gitOutput(cloneDir, 'rev-parse', pending + ':' + STATE)

        and: 'the cell holds the round open at its snapshot, with the pending commit as its last registered commit'
        rounds.snapshot().get() == new ClosedRound(token, snapshot, pending, RoundClose.PENDING)
    }

    // FR10 (design D11, window K1): a registered pending-round snapshot is never a round to verify
    def "FR10: a register naming the box's pending-round snapshot is not read as an interrupted verification"() {
        expect:
        registered() == snapshot
        new SnapshotTipCheck(runner, cloneDir).inspect(TASK).isEmpty()
    }

    // FR10 (design D11): the round's state commit re-admits the snapshot by its pending-round subject
    // and sits on the pending round commit, carrying no pending entry
    def "FR10: the close of a pending box round re-admits its pending-round snapshot and lands on its pending round commit"() {
        given:
        persistence().persistPending(TASK, roundPendingState('job-7'))
        def pending = tip()

        when:
        def key = new AttemptKey(TASK, 'implement', 1)
        persistence().persist(TASK, RoundStates.recorded(key), new ToolTrace(key, []))

        then:
        def stateCommit = tip()
        registered() == stateCommit
        parentsOf(stateCommit) == pending
        !gitOutput(cloneDir, 'show', stateCommit + ':' + STATE).contains('job-7')
    }

    // Scenario: git/git-task-persistence / The closing snapshot sits on the last pending round commit
    def "FR10: the terminal poll of a pending box round snapshots it on its last pending round commit under the round's token"() {
        given: 'the round\'s pending round commit is registered above its pending-round snapshot'
        def token = rounds.opened()
        persistence().persistPending(TASK, roundPendingState('job-7'))
        def pending = tip()

        when: 'the round closes after its terminal poll'
        def closing = snapshotStep.snapshot(TASK, 'implement', 1, RoundClose.VERIFIABLE)

        then: 'the closing snapshot is admitted with its register, its only parent the pending round commit'
        tip() == closing
        registered() == closing
        parentsOf(closing) == pending

        and: 'its subject names the token of the round\'s pending-round snapshot'
        gitOutput(cloneDir, 'log', '-1', '--format=%s', snapshot) == ServiceCommitMessages.pendingSnapshot('implement', 1, token)
        gitOutput(cloneDir, 'log', '-1', '--format=%s', closing) == ServiceCommitMessages.snapshot('implement', 1, token)
        rounds.closed() == new ClosedRound(token, closing, closing, RoundClose.VERIFIABLE)

        and: 'the box\'s state.json lag was settled before the staging, so the snapshot changes nothing'
        changedIn(closing).isEmpty()

        when: 'the round\'s state commit lands'
        def key = new AttemptKey(TASK, 'implement', 1)
        persistence().persist(TASK, RoundStates.recorded(key), new ToolTrace(key, []))

        then:
        parentsOf(tip()) == closing
    }

    // Scenario: git/git-task-persistence / The factory's own commits of a pending round are no gnome commits
    def "FR10: a pending box round whose executor committed during a poll is admitted from its last pending round commit"() {
        given:
        persistence().persistPending(TASK, roundPendingState('job-7'))
        def pending = tip()

        when: 'the executor commits in the box during the terminal poll, and the round closes'
        commit(box.workingCopy, 'polled.txt', 'polled')
        def closing = snapshotStep.snapshot(TASK, 'implement', 1, RoundClose.VERIFIABLE)

        then: 'the admitted range is the executor\'s commit and the closing snapshot above the pending round commit'
        registered() == closing
        gitOutput(cloneDir, 'log', '--format=%s', "${pending}..${closing}").readLines() == [
            ServiceCommitMessages.snapshot('implement', 1, rounds.opened()),
            'polled.txt'
        ]

        and: 'no boundary violation is reported for the box\'s state.json at the round\'s close'
        def key = new AttemptKey(TASK, 'implement', 1)
        persistence().persist(TASK, RoundStates.recorded(key), new ToolTrace(key, []))
        parentsOf(tip()) == closing
    }

    // FR10 (design D11 as amended 2026-11-25): the box medium settles the lag through the one
    //     PendingStateLag, run in the box, and hands back a restore it could not make
    def "FR10: the box medium reports a lag it recognised and could not restore"() {
        given: 'a chain whose last registered commit cannot be read, above the snapshot the box\'s index carries'
        def order = new SnapshotOrder(new AttemptKey(TASK, 'implement', 1), rounds.opened(), 'unused', ['f' * 40, snapshot])

        expect:
        new InBoxSnapshot(box, runner, cloneDir).settle(order).isPresent()
    }
}
