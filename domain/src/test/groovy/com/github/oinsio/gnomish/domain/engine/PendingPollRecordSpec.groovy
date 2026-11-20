package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * The pending poll of one verdict check, an entry of the open round's list (FR2, FR20, NFR-R1 of
 * migrate-verify-to-executor-contract; design D20 as amended 2026-11-12, D21): the check, the
 * attempt, the round token, the leaf-typed key and the instant, compared by content; an attempt
 * number is never negative, and the token is absent only in the in-place run.
 */
class PendingPollRecordSpec extends Specification {

    private static final CheckRef CHECK = new CheckRef(1, UntrustedText.manifest('executor:scm-status:ci'))
    private static final RoundToken TOKEN = RoundToken.of('0123456789abcdef0123456789abcdef01234567')
    private static final Instant SINCE = Instant.parse('2026-11-12T09:00:00Z')

    // FR2, FR20: the record exposes its components as constructed and is compared by content
    def "a pending poll record exposes the check, attempt, token, key and instant it was built with"() {
        when:
        def record = new PendingPollRecord(CHECK, 0, token, new PendingKey('run-1'), SINCE)

        then:
        record.check() == CHECK
        record.attempt() == 0
        record.token() == token
        record.key() == new PendingKey('run-1')
        record.since() == SINCE
        record == new PendingPollRecord(CHECK, 0, token, new PendingKey('run-1'), SINCE)

        where: 'a branch-mode round names its token; the in-place run has none'
        token << [TOKEN, null]
    }

    // FR20, D21: the round is part of the identity — the same key under another token is another entry
    def "two records differing only in their round token are not equal"() {
        expect:
        new PendingPollRecord(CHECK, 0, TOKEN, new PendingKey('run-1'), SINCE) !=
                new PendingPollRecord(CHECK, 0, RoundToken.of('fedcba9876543210fedcba9876543210fedcba98'),
                new PendingKey('run-1'), SINCE)
    }

    // FR2: an attempt number counts rounds from zero
    def "a negative attempt is refused"() {
        when:
        new PendingPollRecord(CHECK, -1, TOKEN, new PendingKey('run-1'), SINCE)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'PendingPollRecord.attempt must not be negative'
    }
}
