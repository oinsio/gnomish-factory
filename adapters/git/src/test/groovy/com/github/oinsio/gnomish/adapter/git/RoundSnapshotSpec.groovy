package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.app.port.git.CurrentRound
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * FR10 of extract-agent-round-runner (design D11 as amended 2026-11-25): the one snapshot step hands
 * its medium the round's registered chain from the run's cell — so a round that went pending is
 * judged from its last pending round commit — and settles the {@code state.json} lag of the pending
 * round commits before the medium judges or commits anything. The real media are
 * {@code HostPendingRoundCommitSpec}'s and {@code InBoxPendingRoundCommitSpec}'s.
 */
class RoundSnapshotSpec extends Specification {

    static final String OPEN = 'a' * 40
    static final String PENDING_SNAPSHOT = 'b' * 40
    static final String PENDING = 'c' * 40
    static final String CLOSING = 'd' * 40

    CurrentRound cell = new CurrentRound()
    List<String> calls = []
    List<SnapshotOrder> orders = []

    private SnapshotMedium medium(Optional<UntrustedText> settled) {
        [
            settle: { SnapshotOrder order ->
                calls << 'settle'
                orders << order
                settled
            },
            judge : { SnapshotOrder order ->
                calls << 'judge'
                new RangeVerdict.Clean()
            },
            commit: { SnapshotOrder order ->
                calls << 'commit'
                CLOSING
            }
        ] as SnapshotMedium
    }

    private void pendingRound() {
        cell.open(RoundToken.of(OPEN))
        cell.snapshotted(PENDING_SNAPSHOT, RoundClose.PENDING)
        cell.pendingRegistered(PENDING)
    }

    def "FR10: a round that went pending is snapshotted from its last pending round commit, the lag settled first"() {
        given:
        pendingRound()

        when:
        def snapshot = new RoundSnapshot(medium(Optional.empty()), cell).take('T-1', 'build', 0, RoundClose.VERIFIABLE)

        then: 'the order carries the cell\'s chain, its base the last pending round commit, its subject the round\'s token'
        snapshot == Optional.of(CLOSING)
        calls == ['settle', 'judge', 'commit']
        orders.first().chain() == [PENDING, PENDING_SNAPSHOT]
        orders.first().base() == PENDING
        orders.first().subject() == ServiceCommitMessages.snapshot('build', 0, RoundToken.of(OPEN))

        and: 'the closing snapshot is the round\'s registered commit now'
        cell.registeredChain() == [CLOSING]
    }

    def "FR10: a round that registered nothing is judged from the tip it opened on"() {
        given:
        cell.open(RoundToken.of(OPEN))

        when:
        new RoundSnapshot(medium(Optional.empty()), cell).take('T-1', 'build', 0, RoundClose.PENDING)

        then:
        orders.first().chain().isEmpty()
        orders.first().base() == OPEN
    }

    def "FR10: a lag that cannot be settled fails the snapshot before anything is judged or committed"() {
        given:
        pendingRound()

        when:
        new RoundSnapshot(medium(Optional.of(UntrustedText.subprocess('fatal: locked'))), cell)
                .take('T-1', 'build', 0, RoundClose.VERIFIABLE)

        then:
        def failure = thrown(GitPersistFailedException)
        failure.message.contains('pending-commit lag')
        failure.message.contains('locked')
        calls == ['settle']
        cell.registeredChain() == [PENDING, PENDING_SNAPSHOT]
    }
}
