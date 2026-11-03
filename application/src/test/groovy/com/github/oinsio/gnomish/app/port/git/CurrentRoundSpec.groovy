package com.github.oinsio.gnomish.app.port.git

import com.github.oinsio.gnomish.domain.engine.RoundToken
import spock.lang.Specification

/**
 * FR13, FR15 of make-checkpoint-gate-durable (design D10 as amended 2026-10-07): the per-run cell
 * holding the round in flight as one identity — opened by its token, closed by its snapshot,
 * restored on resume through those same two transitions — so no reader is ever handed half a round.
 */
class CurrentRoundSpec extends Specification {

    static final RoundToken FIRST = RoundToken.of('0a1b2c')
    static final RoundToken SECOND = RoundToken.of('0d0e0f')

    def "FR13: an opened round yields its token, and is not closed until its snapshot is recorded"() {
        given:
        def rounds = new CurrentRound()

        when:
        rounds.open(FIRST)

        then:
        rounds.opened() == FIRST

        when:
        rounds.closed()

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains('not closed')
    }

    def "FR13: the snapshot closes the open round under the token it opened with"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)

        when:
        rounds.snapshotted('snap-1')

        then:
        rounds.closed() == new ClosedRound(FIRST, 'snap-1')
        rounds.opened() == FIRST
    }

    def "FR13: with no round opened, every read and the snapshot refuse: #operation"() {
        when:
        action(new CurrentRound())

        then:
        def refused = thrown(IllegalStateException)
        refused.message.contains('no round was opened')

        where:
        operation | action
        'opened()' | { CurrentRound r -> r.opened() }
        'closed()' | { CurrentRound r -> r.closed() }
        'snapshotted' | { CurrentRound r -> r.snapshotted('snap-1') }
    }

    def "FR13: a second open replaces the round, dropping the previous round's snapshot"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)
        rounds.snapshotted('snap-1')

        when:
        rounds.open(SECOND)

        then:
        rounds.opened() == SECOND

        when:
        rounds.closed()

        then:
        thrown(IllegalStateException)

        when:
        rounds.snapshotted('snap-2')

        then:
        rounds.closed() == new ClosedRound(SECOND, 'snap-2')
    }

    def "FR15: restore is open-then-snapshotted over the recorded round, replacing whatever was held"() {
        given:
        def pending = new PendingVerification('snap-9', 'implement', 2, SECOND, Optional.empty())
        def restored = new CurrentRound()
        restored.open(FIRST)
        restored.snapshotted('snap-1')
        def live = new CurrentRound()

        when:
        restored.restore(pending)
        live.open(SECOND)
        live.snapshotted('snap-9')

        then:
        restored.closed() == live.closed()
        restored.opened() == live.opened()
        restored.closed() == new ClosedRound(SECOND, 'snap-9')
    }

    // FR14, FR21 of define-executor-contract: the engine takes the open round's token once
    def "FR21: the open round's token is taken once, and opening the next round makes its own takeable"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)

        expect: 'the first take yields the open round\'s token'
        rounds.takeToken() == Optional.of(FIRST)

        when: 'a second record would take the same round\'s token again'
        rounds.takeToken()

        then:
        thrown(IllegalStateException)

        when: 'the snapshot keeps the taken mark, and the next round opens'
        rounds.snapshotted('snap-1')
        rounds.open(SECOND)

        then:
        rounds.takeToken() == Optional.of(SECOND)
    }

    def "FR21: a snapshot after the take does not make the round's token takeable again"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)
        rounds.takeToken()
        rounds.snapshotted('snap-1')

        when:
        rounds.takeToken()

        then:
        thrown(IllegalStateException)
        rounds.closed() == new ClosedRound(FIRST, 'snap-1')
    }

    def "FR21: no token is taken before a round opened"() {
        when:
        new CurrentRound().takeToken()

        then:
        thrown(IllegalStateException)
    }

    def "FR15, FR21: a restored round's token is takeable"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)
        rounds.takeToken()

        when:
        rounds.restore(new PendingVerification('snap-9', 'implement', 2, SECOND, Optional.empty()))

        then:
        rounds.takeToken() == Optional.of(SECOND)
    }
}
