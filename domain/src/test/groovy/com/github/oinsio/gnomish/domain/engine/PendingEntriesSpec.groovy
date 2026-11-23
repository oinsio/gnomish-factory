package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant
import spock.lang.Specification

/**
 * FR10, NFR-R3 of extract-agent-round-runner (design D11; crash-consistency item 13): an entry read
 * off the registered commit is the open round's own only when its token names the round that commit
 * holds open and its attempt is that round's; a {@code Round} entry naming another round is refused
 * and never handed to a poll, and a {@code Check} entry is never a round's.
 */
class PendingEntriesSpec extends Specification {

    private static final RoundToken OPEN = RoundToken.of('a' * 40)
    private static final RoundToken OTHER = RoundToken.of('b' * 40)
    private static final Instant SINCE = Instant.parse('2026-11-23T09:00:00Z')
    private static final PendingSubject CHECK = new PendingSubject.Check(new CheckRef(0, UntrustedText.manifest('executor:ci')))

    private static PendingPollRecord entry(PendingSubject subject, RoundToken token, int attempt, String key = 'k') {
        new PendingPollRecord(subject, attempt, token, new PendingKey(key), SINCE)
    }

    // D11, item 13: identity, never presence — the token names the round, the attempt confirms it
    def "an entry #why #verdict the open round's"() {
        expect:
        PendingEntries.ofRound(entry(subject, token, attempt), 2, open) == ours

        where:
        why | subject | token | attempt | open | ours
        'of the open token and attempt' | PendingSubject.ROUND | OPEN | 2 | OPEN | true
        'of the open token and attempt (check)' | CHECK | OPEN | 2 | OPEN | true
        'of another round token' | PendingSubject.ROUND | OTHER | 2 | OPEN | false
        'of another attempt under the open token' | PendingSubject.ROUND | OPEN | 1 | OPEN | false
        'with no token' | PendingSubject.ROUND | null | 2 | OPEN | false
        'when no round is open by token' | PendingSubject.ROUND | null | 2 | null | false
        verdict = ours ? 'is' : 'is not'
    }

    // FR10, NFR-R3, D11: the open round's round entry is the one a pickup polls
    def "the open round's round entry is accepted, beside the checks' entries"() {
        given:
        def own = entry(PendingSubject.ROUND, OPEN, 2, 'k-round')

        expect:
        PendingEntries.roundEntry([
            entry(CHECK, OPEN, 2, 'k-check'),
            own
        ], 2, OPEN) == Optional.of(own)
    }

    // FR10, NFR-R3, D11: a round entry whose token does not name the open round is refused, never polled
    def "a round entry #why is refused"() {
        expect:
        PendingEntries.roundEntry([
            entry(PendingSubject.ROUND, token, attempt)
        ], 2, open) == Optional.empty()

        where:
        why | token | attempt | open
        'of another round token' | OTHER | 2 | OPEN
        'of another attempt under the open token' | OPEN | 1 | OPEN
        'with no token' | null | 2 | OPEN
        'when no round is open by token' | OPEN | 2 | null
    }

    // D11: a check's entry of the open round is a verification's, never the round's
    def "a check's entry of the open round is not the round's entry"() {
        expect:
        PendingEntries.roundEntry([entry(CHECK, OPEN, 2)], 2, OPEN) == Optional.empty()
        PendingEntries.roundEntry([], 2, OPEN) == Optional.empty()
    }
}
