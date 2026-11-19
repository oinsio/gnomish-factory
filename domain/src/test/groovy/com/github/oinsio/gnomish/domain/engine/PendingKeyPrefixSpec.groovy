package com.github.oinsio.gnomish.domain.engine

import com.github.oinsio.gnomish.domain.engine.port.PendingTurn
import com.github.oinsio.gnomish.pendingkey.PendingKey
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * NFR-O1, UX3 of migrate-verify-to-executor-contract: the one rendering of a pending key the log and
 * the ledger show — its first sixteen code points, {@code ...} after a longer key — carried in the
 * family of the key's source.
 */
class PendingKeyPrefixSpec extends Specification {

    // NFR-O1, UX3: a key of at most sixteen code points is shown whole; a longer one is cut and marked
    def "the prefix of #why is #shown"() {
        expect:
        PendingKeyPrefix.of(new PendingTurn(new PendingKey(key), PendingTurn.Origin.ISSUED)) == UntrustedText.subprocess(shown)

        where:
        why | key || shown
        'a short key' | 'k-1' || 'k-1'
        'a key of exactly sixteen' | 'a' * 16 || 'a' * 16
        'a key of seventeen' | 'a' * 16 + 'b' || 'a' * 16 + '...'
        'a key of astral code points' | '😀' * 17 || '😀' * 16 + '...'
        'a key whose seventeenth is astral' | 'a' * 16 + '😀' || 'a' * 16 + '...'
    }

    // UX3: a recorded key was read off the branch; an issued one is the executor's output
    def "a #origin key's prefix is carried as #family text"() {
        expect:
        PendingKeyPrefix.of(new PendingTurn(new PendingKey('k-1'), origin)).provenance() == family

        where:
        origin || family
        PendingTurn.Origin.RECORDED || Provenance.BRANCH_DOCUMENT
        PendingTurn.Origin.ISSUED || Provenance.SUBPROCESS
    }
}
