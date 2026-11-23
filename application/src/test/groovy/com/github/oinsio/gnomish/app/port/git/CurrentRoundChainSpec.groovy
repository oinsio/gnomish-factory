package com.github.oinsio.gnomish.app.port.git

import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading
import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.domain.engine.RoundToken
import spock.lang.Specification

/**
 * FR3, FR11 of migrate-verify-to-executor-contract (design D14 as amended 2026-11-13; crash-consistency
 * item 14): the round's registered chain the adoption of a recorded key reads — the snapshot, then each
 * pending commit landed on it at the head — held by the run's one {@link CurrentRound}, restored from the
 * chain the resume's own walk passed through the live round's transitions, never walked again; and the
 * {@link PendingVerification} that carries it refuses a chain that does not span the round.
 */
class CurrentRoundChainSpec extends Specification {

    static final RoundToken TOKEN = RoundToken.of('0d0e0f')

    // FR3, D14: the live round's chain grows at its head with each pending commit
    def "FR3: a live round's chain is its snapshot, then each pending commit landed on it, last registered first"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(TOKEN)

        expect:
        rounds.registeredChain() == []

        when:
        rounds.snapshotted('snap-1', RoundClose.VERIFIABLE)

        then:
        rounds.registeredChain() == ['snap-1']

        when:
        rounds.pendingRegistered('pend-1')
        rounds.pendingRegistered('pend-2')

        then:
        rounds.registeredChain() == ['pend-2', 'pend-1', 'snap-1']
        rounds.lastRegistered() == Optional.of('pend-2')
    }

    // FR3, D14: restore runs the live transitions over the recorded chain, so the two cells are equal
    def "FR3: a restored round holds the recorded chain, the same as the live round that landed it"() {
        given:
        def restored = new CurrentRound()
        def live = new CurrentRound()

        when:
        restored.restore(new PendingVerification('snap-9', 'build', 2, TOKEN, new AnswerFileReading.Absent(), 'pend-b',
                ['pend-b', 'pend-a', 'snap-9']))
        live.open(TOKEN)
        live.snapshotted('snap-9', RoundClose.VERIFIABLE)
        live.pendingRegistered('pend-a')
        live.pendingRegistered('pend-b')

        then:
        restored.registeredChain() == ['pend-b', 'pend-a', 'snap-9']
        restored.registeredChain() == live.registeredChain()
        restored.closed() == live.closed()

        when: 'the resumed round lands its next pending commit'
        restored.pendingRegistered('pend-c')

        then:
        restored.registeredChain() == [
            'pend-c',
            'pend-b',
            'pend-a',
            'snap-9'
        ]
    }

    def "FR3: no round, or a round with no snapshot, has an empty chain; the local default has none"() {
        given:
        def open = new CurrentRound()
        open.open(TOKEN)

        expect:
        new CurrentRound().registeredChain() == []
        open.registeredChain() == []
        LastRegisteredCommit.NONE.registeredChain() == []
        LastRegisteredCommit.NONE.lastRegistered() == Optional.empty()
    }

    // FR3, D14: the record's chain runs from its registered commit down to its snapshot
    def "FR3: a pending verification whose chain #why is refused"() {
        when:
        new PendingVerification('snap', 'build', 0, TOKEN, new AnswerFileReading.Absent(), 'pend', chain)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.startsWith('PendingVerification.chain must run from the registered commit down to the snapshot')

        where:
        why | chain
        'is empty' | []
        'does not start at the registered commit' | ['other', 'snap']
        'does not end at the snapshot' | ['pend', 'other']
    }

    def "FR3: a pending verification copies its chain"() {
        given:
        def chain = ['pend', 'snap']

        when:
        def verification = new PendingVerification('snap', 'build', 0, TOKEN, new AnswerFileReading.Absent(), 'pend', chain)
        chain.add('late')

        then:
        verification.chain() == ['pend', 'snap']
    }
}
