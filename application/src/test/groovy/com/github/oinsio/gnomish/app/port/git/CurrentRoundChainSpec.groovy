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
        restored.restore(new PendingVerification('snap-9', 'build', 2, TOKEN, RoundClose.VERIFIABLE, new AnswerFileReading.Absent(), 'pend-b',
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
        new PendingVerification('snap', 'build', 0, TOKEN, RoundClose.VERIFIABLE, new AnswerFileReading.Absent(), 'pend', chain)

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
        def verification = new PendingVerification('snap', 'build', 0, TOKEN, RoundClose.VERIFIABLE, new AnswerFileReading.Absent(), 'pend', chain)
        chain.add('late')

        then:
        verification.chain() == ['pend', 'snap']
    }

    // FR11, NFR-R3 of extract-agent-round-runner (D11, "Resume"): the bottom names the kind, and a
    //     pending round stands on at least one pending round commit
    def "FR11: a pending verification of kind #close over #chain is refused: #why"() {
        when:
        new PendingVerification('snap', 'build', 0, TOKEN, close, new AnswerFileReading.Absent(), chain.first(), chain)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains(why)

        where:
        close | chain || why
        RoundClose.UNVERIFIABLE | ['snap'] || 'resumed by no one'
        RoundClose.UNVERIFIABLE | ['pend', 'snap'] || 'resumed by no one'
        RoundClose.PENDING | ['snap'] || 'a pending-round snapshot alone is re-driven'
    }

    def "FR11: a pending round above its pending-round snapshot, and an interrupted verification at its snapshot, are values"() {
        expect:
        new PendingVerification('snap', 'build', 0, TOKEN, RoundClose.PENDING, new AnswerFileReading.Absent(), 'pend',
                ['pend', 'snap']).close() == RoundClose.PENDING
        new PendingVerification('snap', 'build', 0, TOKEN, RoundClose.VERIFIABLE, new AnswerFileReading.Absent(), 'snap',
                ['snap']).close() == RoundClose.VERIFIABLE
    }

    // FR11, NFR-R3 (D11, "Resume"; crash-consistency item 14): a pending round is restored through the
    //     live round's transitions as the live round left it once its start went pending
    def "FR11: a restored pending round equals the live round after its pending round commits, and its token is the pending one"() {
        given:
        def restored = new CurrentRound()
        def live = new CurrentRound()

        when:
        restored.restore(new PendingVerification('psnap', 'build', 2, TOKEN, RoundClose.PENDING, new AnswerFileReading.Absent(),
                'pend-b', ['pend-b', 'pend-a', 'psnap']))
        live.open(TOKEN)
        live.snapshotted('psnap', RoundClose.PENDING)
        live.pendingRegistered('pend-a')
        live.pendingRegistered('pend-b')

        then:
        restored.snapshot() == live.snapshot()
        restored.snapshot().get().close() == RoundClose.PENDING
        restored.registeredChain() == live.registeredChain()

        and: 'the restored round is a pending one to continue until its token is taken'
        restored.pendingToken() == Optional.of(TOKEN)
        restored.takeToken() == Optional.of(TOKEN)
        restored.pendingToken() == Optional.empty()
    }

    def "FR11: no pending token while #state"() {
        given:
        def rounds = new CurrentRound()
        prepare(rounds)

        expect:
        rounds.pendingToken() == Optional.empty()

        where:
        state | prepare
        'no round was opened' | { CurrentRound r -> }
        'the open round has no snapshot' | { CurrentRound r ->
            r.open(TOKEN)
        }
        'the open round closed verifiable' | { CurrentRound r ->
            r.open(TOKEN); r.snapshotted('s', RoundClose.VERIFIABLE)
        }
        'the open round closed unverifiable' | { CurrentRound r ->
            r.open(TOKEN); r.snapshotted('s', RoundClose.UNVERIFIABLE)
        }
        'a live pending round\'s token was taken' | { CurrentRound r ->
            r.open(TOKEN); r.snapshotted('s', RoundClose.PENDING); r.takeToken()
        }
    }

    def "FR11: an interrupted verification is restored verifiable, never a pending round"() {
        given:
        def rounds = new CurrentRound()

        when:
        rounds.restore(new PendingVerification('snap', 'build', 0, TOKEN, RoundClose.VERIFIABLE, new AnswerFileReading.Absent(),
                'pend', ['pend', 'snap']))

        then:
        rounds.snapshot().get().close() == RoundClose.VERIFIABLE
        rounds.pendingToken() == Optional.empty()
    }
}
