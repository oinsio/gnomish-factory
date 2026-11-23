package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * The pending poll of one subject, an entry of the open round's list (FR2, FR20, NFR-R1 of
 * migrate-verify-to-executor-contract; design D20 as amended 2026-11-12, D21; FR10, NFR-R3 of
 * extract-agent-round-runner, design D11): the subject — a check or the round —, the attempt, the
 * round token, the leaf-typed key and the instant, compared by content; an attempt number is never
 * negative, and the token is absent only in the in-place run.
 */
class PendingPollRecordSpec extends Specification {

    private static final CheckRef CHECK = new CheckRef(1, UntrustedText.manifest('executor:scm-status:ci'))
    private static final RoundToken TOKEN = RoundToken.of('0123456789abcdef0123456789abcdef01234567')
    private static final Instant SINCE = Instant.parse('2026-11-12T09:00:00Z')

    // FR2, FR20; D11 of extract-agent-round-runner: the record exposes its components as
    //     constructed and is compared by content, for either subject
    def "a pending poll record exposes the #subject.class.simpleName subject, attempt, token, key and instant it was built with"() {
        when:
        def record = new PendingPollRecord(subject, 0, token, new PendingKey('run-1'), SINCE)

        then:
        record.subject() == subject
        record.attempt() == 0
        record.token() == token
        record.key() == new PendingKey('run-1')
        record.since() == SINCE
        record == new PendingPollRecord(subject, 0, token, new PendingKey('run-1'), SINCE)

        where: 'a branch-mode round names its token; the in-place run has none'
        subject | token
        new PendingSubject.Check(CHECK) | TOKEN
        new PendingSubject.Check(CHECK) | null
        PendingSubject.ROUND | TOKEN
    }

    // FR20, D21: the round is part of the identity — the same key under another token is another entry
    def "two records differing only in their round token are not equal"() {
        expect:
        new PendingPollRecord(new PendingSubject.Check(CHECK), 0, TOKEN, new PendingKey('run-1'), SINCE) !=
                new PendingPollRecord(new PendingSubject.Check(CHECK), 0,
                RoundToken.of('fedcba9876543210fedcba9876543210fedcba98'), new PendingKey('run-1'), SINCE)
    }

    // D11 of extract-agent-round-runner: the subject is part of the identity — a check's entry is
    //     never the round's, and every round subject is the same subject
    def "a check's entry and the round's entry of one key are not equal, and two round subjects are"() {
        expect:
        new PendingPollRecord(new PendingSubject.Check(CHECK), 0, TOKEN, new PendingKey('run-1'), SINCE) !=
                new PendingPollRecord(PendingSubject.ROUND, 0, TOKEN, new PendingKey('run-1'), SINCE)
        new PendingSubject.Round() == PendingSubject.ROUND
    }

    // FR2: an attempt number counts rounds from zero
    def "a negative attempt is refused"() {
        when:
        new PendingPollRecord(new PendingSubject.Check(CHECK), -1, TOKEN, new PendingKey('run-1'), SINCE)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'PendingPollRecord.attempt must not be negative'
    }
}
