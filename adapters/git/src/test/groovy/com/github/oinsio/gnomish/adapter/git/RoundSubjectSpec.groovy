package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.app.port.agent.RoundClose
import com.github.oinsio.gnomish.domain.engine.AttemptKey
import com.github.oinsio.gnomish.domain.engine.CheckRef
import com.github.oinsio.gnomish.domain.engine.PendingPollRecord
import com.github.oinsio.gnomish.domain.engine.PendingSubject
import com.github.oinsio.gnomish.domain.engine.RoundToken
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * FR15 of make-checkpoint-gate-durable; FR19, FR20 of migrate-verify-to-executor-contract; FR10, FR11
 * of extract-agent-round-runner: the round subjects — snapshot, unverified snapshot, pending-round
 * snapshot, pending — are a parsing contract, so their exact text is pinned here, beside the parse
 * that reads their tail back (crash-consistency item 14).
 */
class RoundSubjectSpec extends Specification {

    // FR15 of make-checkpoint-gate-durable (design D10): the one parsed subject names the round's
    // token after its stage and round, separated by one space — SnapshotTipCheck reads it back.
    def "FR15: snapshot(stage, round, token) names the round token after stage#round"() {
        expect:
        RoundSubject.snapshot('work', 0, RoundToken.of('0a1b2c')) == 'gnomish: snapshot work#0 0a1b2c'
    }

    // FR19 of migrate-verify-to-executor-contract (design D22): the unverified subject stays in the
    // service namespace and outside the snapshot prefix, so the recogniser never reads it
    def "FR19: unverifiedSnapshot(stage, round, token) is a service subject outside the snapshot prefix"() {
        when:
        def subject = RoundSubject.unverifiedSnapshot('work', 0, RoundToken.of('0a1b2c'))

        then:
        subject == 'gnomish: unverified-snapshot work#0 0a1b2c'
        subject.startsWith(GnomeRangeInspection.SERVICE_SUBJECT)
        !subject.startsWith(RoundSubject.SNAPSHOT_PREFIX)
    }

    // FR10 of extract-agent-round-runner (design D11): the pending-round snapshot stays in the service
    // namespace and outside both prefixes the recogniser reads, so it is never read as a round to verify
    def "FR10: pendingSnapshot(stage, round, token) is a service subject outside the snapshot and pending prefixes"() {
        when:
        def subject = RoundSubject.pendingSnapshot('work', 0, RoundToken.of('0a1b2c'))

        then:
        subject == 'gnomish: pending-snapshot work#0 0a1b2c'
        subject.startsWith(GnomeRangeInspection.SERVICE_SUBJECT)
        !subject.startsWith(RoundSubject.SNAPSHOT_PREFIX)
        !subject.startsWith(RoundSubject.PENDING_PREFIX)
    }

    // FR19 (design D22): the step's one choice of the subject by the round's close
    def "FR19: the snapshot step names a #close close #expected"() {
        expect:
        RoundSnapshot.subject(new AttemptKey('T-1', 'work', 2), RoundToken.of('0a1b2c'), close) == expected

        where:
        close || expected
        RoundClose.VERIFIABLE || 'gnomish: snapshot work#2 0a1b2c'
        RoundClose.UNVERIFIABLE || 'gnomish: unverified-snapshot work#2 0a1b2c'
        RoundClose.PENDING || 'gnomish: pending-snapshot work#2 0a1b2c'
    }

    // FR20 of migrate-verify-to-executor-contract (design D21): the stage from the position, round and
    // token from the list's last entry
    def "FR20: a pending commit's subject names the stage, the last entry's round and its token"() {
        given:
        def last = new PendingPollRecord(subject, 3, RoundToken.of('0a1b2c'), new PendingKey('run-1'), Instant.EPOCH)

        expect:
        RoundSubject.pending('work', last) == 'gnomish: pending work#3 0a1b2c'

        where: 'D11 of extract-agent-round-runner: the subject is the same for a check and for the round'
        subject << [
            new PendingSubject.Check(new CheckRef(1, UntrustedText.factory('ci'))),
            PendingSubject.ROUND
        ]
    }

    def "FR20: an entry of #named with no round token has no pending subject"() {
        when:
        RoundSubject.pending('work', new PendingPollRecord(subject, 3, null, new PendingKey('run-1'),
                Instant.EPOCH))

        then:
        def refused = thrown(IllegalArgumentException)
        refused.message.contains('the entry of ' + named + ' carries none')

        where:
        subject | named
        new PendingSubject.Check(new CheckRef(4, UntrustedText.factory('ci'))) | 'check 4'
        PendingSubject.ROUND | 'the round'
    }

    // FR11 of extract-agent-round-runner (D11, "Resume"): the parse reads back exactly the round the
    // writer spelled, past each prefix the recogniser reads
    def "FR11: the parse reads back the round #writer wrote"() {
        given:
        def token = RoundToken.of('0a1b2c')

        when:
        def rest = render(token).substring(prefix.length())

        then:
        RoundSubject.parse('T-1', rest) == Optional.of(new RoundSubject(new AttemptKey('T-1', 'my#stage', 7), token))

        where:
        writer | prefix | render
        'snapshot' | RoundSubject.SNAPSHOT_PREFIX | { RoundToken t ->
            RoundSubject.snapshot('my#stage', 7, t)
        }
        'pendingSnapshot' | RoundSubject.PENDING_SNAPSHOT_PREFIX | { RoundToken t ->
            RoundSubject.pendingSnapshot('my#stage', 7, t)
        }
        'pending' | RoundSubject.PENDING_PREFIX | { RoundToken t ->
            RoundSubject.pending('my#stage', 7, t)
        }
    }

    def "FR11: a tail the writer could not have produced is refused: #why"() {
        expect:
        RoundSubject.parse('T-1', rest).isEmpty()

        where:
        why | rest
        'no token' | 'work#3'
        'a token not in hex' | 'work#3 xyz'
        'a blank stage' | '#3 0a1b2c'
        'a round past int range' | 'work#99999999999 0a1b2c'
    }
}
