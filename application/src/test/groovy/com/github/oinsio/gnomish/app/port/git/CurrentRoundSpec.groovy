package com.github.oinsio.gnomish.app.port.git

import com.github.oinsio.gnomish.app.port.agent.AnswerFileReading
import com.github.oinsio.gnomish.app.port.agent.RoundClose
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
        rounds.snapshotted('snap-1', RoundClose.VERIFIABLE)

        then:
        rounds.closed() == new ClosedRound(FIRST, 'snap-1', 'snap-1', RoundClose.VERIFIABLE)
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
        'snapshotted' | { CurrentRound r ->
            r.snapshotted('snap-1', RoundClose.VERIFIABLE)
        }
        'snapshot()' | { CurrentRound r -> r.snapshot() }
    }

    // FR19 of migrate-verify-to-executor-contract (design D22): the host persistence's range base
    // reads the round's snapshot while the round may still be open; the snapshot is the round's
    // registered commit, and the round's close rides it
    def "FR19: an open round has no snapshot yet; its snapshot is its registered commit and keeps its close: #close"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)

        expect:
        rounds.snapshot().isEmpty()

        when:
        rounds.snapshotted('snap-1', close)

        then:
        rounds.snapshot() == Optional.of(new ClosedRound(FIRST, 'snap-1', 'snap-1', close))
        rounds.closed().registered() == 'snap-1'
        rounds.closed().close() == close

        where:
        close << RoundClose.values()
    }

    // FR19 of migrate-verify-to-executor-contract: only a snapshot subject is resumed, so a
    // restored round closed verifiable
    def "FR19: a restored round closed verifiable at its snapshot"() {
        given:
        def rounds = new CurrentRound()

        when:
        rounds.restore(new PendingVerification('snap-9', 'build', 2, SECOND, new AnswerFileReading.Absent(), 'snap-9', ['snap-9']))

        then:
        rounds.closed() == new ClosedRound(SECOND, 'snap-9', 'snap-9', RoundClose.VERIFIABLE)
    }

    // FR20 of migrate-verify-to-executor-contract (design D21): a pickup of a registered pending
    // commit restores both commits — the checks judge the snapshot, the round's close sits on the
    // pending commit — through the live round's own transitions
    def "FR20: a restored pending commit is the round's last registered commit, the snapshot its attempt commit"() {
        given:
        def restored = new CurrentRound()
        def live = new CurrentRound()

        when:
        restored.restore(new PendingVerification('snap-9', 'build', 2, SECOND, new AnswerFileReading.Absent(), 'pend-9', ['pend-9', 'snap-9']))
        live.open(SECOND)
        live.snapshotted('snap-9', RoundClose.VERIFIABLE)
        live.pendingRegistered('pend-9')

        then:
        restored.closed() == new ClosedRound(SECOND, 'snap-9', 'pend-9', RoundClose.VERIFIABLE)
        restored.closed() == live.closed()
        restored.lastRegistered() == Optional.of('pend-9')
        restored.takeToken() == Optional.of(SECOND)
    }

    def "FR13: a second open replaces the round, dropping the previous round's snapshot"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)
        rounds.snapshotted('snap-1', RoundClose.VERIFIABLE)

        when:
        rounds.open(SECOND)

        then:
        rounds.opened() == SECOND

        when:
        rounds.closed()

        then:
        thrown(IllegalStateException)

        when:
        rounds.snapshotted('snap-2', RoundClose.VERIFIABLE)

        then:
        rounds.closed() == new ClosedRound(SECOND, 'snap-2', 'snap-2', RoundClose.VERIFIABLE)
    }

    def "FR15: restore is open-then-snapshotted over the recorded round, replacing whatever was held"() {
        given:
        def pending = new PendingVerification('snap-9', 'implement', 2, SECOND, new AnswerFileReading.Absent(), 'snap-9', ['snap-9'])
        def restored = new CurrentRound()
        restored.open(FIRST)
        restored.snapshotted('snap-1', RoundClose.VERIFIABLE)
        def live = new CurrentRound()

        when:
        restored.restore(pending)
        live.open(SECOND)
        live.snapshotted('snap-9', RoundClose.VERIFIABLE)

        then:
        restored.closed() == live.closed()
        restored.opened() == live.opened()
        restored.closed() == new ClosedRound(SECOND, 'snap-9', 'snap-9', RoundClose.VERIFIABLE)
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
        rounds.snapshotted('snap-1', RoundClose.VERIFIABLE)
        rounds.open(SECOND)

        then:
        rounds.takeToken() == Optional.of(SECOND)
    }

    def "FR21: a snapshot after the take does not make the round's token takeable again"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)
        rounds.takeToken()
        rounds.snapshotted('snap-1', RoundClose.VERIFIABLE)

        when:
        rounds.takeToken()

        then:
        thrown(IllegalStateException)
        rounds.closed() == new ClosedRound(FIRST, 'snap-1', 'snap-1', RoundClose.VERIFIABLE)
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
        rounds.restore(new PendingVerification('snap-9', 'implement', 2, SECOND, new AnswerFileReading.Absent(), 'snap-9', ['snap-9']))

        then:
        rounds.takeToken() == Optional.of(SECOND)
    }

    // FR20 of migrate-verify-to-executor-contract (design D21): a pending commit moves only the
    // round's last registered commit; the snapshot the checks judge stays
    def "FR20: a landed pending commit becomes the round's last registered commit, its snapshot unchanged"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)
        rounds.snapshotted('snap-1', RoundClose.VERIFIABLE)

        when:
        rounds.pendingRegistered('pend-1')
        rounds.pendingRegistered('pend-2')

        then:
        rounds.closed() == new ClosedRound(FIRST, 'snap-1', 'pend-2', RoundClose.VERIFIABLE)
        rounds.lastRegistered() == Optional.of('pend-2')
    }

    def "FR20: a pending commit with no snapshot below it is refused"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)

        when:
        rounds.pendingRegistered('pend-1')

        then:
        thrown(IllegalStateException)
        rounds.snapshot().isEmpty()
    }

    def "FR20: a token taken before a pending commit stays taken"() {
        given:
        def rounds = new CurrentRound()
        rounds.open(FIRST)
        rounds.takeToken()
        rounds.snapshotted('snap-1', RoundClose.VERIFIABLE)
        rounds.pendingRegistered('pend-1')

        when:
        rounds.takeToken()

        then:
        thrown(IllegalStateException)
    }

    def "FR20: the last registered commit is #expected #moment"() {
        expect:
        prepare.call().lastRegistered() == expected

        where:
        moment | prepare || expected
        'before any round' | { -> new CurrentRound() } || Optional.empty()
        'for an open round with no snapshot' | {
            -> def r = new CurrentRound(); r.open(FIRST); r
        } || Optional.empty()
        'the snapshot once it landed' | {
            -> def r = new CurrentRound(); r.open(FIRST); r.snapshotted('snap-1', RoundClose.VERIFIABLE); r
        } || Optional.of('snap-1')
        'gone when the next round opens' | {
            -> def r = new CurrentRound(); r.open(FIRST); r.snapshotted('snap-1', RoundClose.VERIFIABLE); r.pendingRegistered('p'); r.open(SECOND); r
        } || Optional.empty()
    }

    def "FR20: the local default of the role holds no commit"() {
        expect:
        LastRegisteredCommit.NONE.lastRegistered().isEmpty()
    }
}
