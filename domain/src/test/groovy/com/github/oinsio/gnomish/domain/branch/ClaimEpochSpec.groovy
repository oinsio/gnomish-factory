package com.github.oinsio.gnomish.domain.branch

import spock.lang.Specification

/**
 * FR13, NFR-S1 of harden-task-branch-contract: the claim epoch is an opaque ordered token — readers
 * only ever compare two of them — and it carries a counter and nothing else.
 */
class ClaimEpochSpec extends Specification {

    // FR13: staleness is one comparison, stated once on the type rather than at each reader.
    def "an epoch is stale only against a strictly newer one"() {
        expect:
        new ClaimEpoch(tip).isStaleAgainst(new ClaimEpoch(live)) == stale

        where:
        tip | live || stale
        1L | 2L || true
        2L | 2L || false
        3L | 2L || false
        0L | 1L || true
    }

    // FR13: monotonic ordering is what makes a reclaim's epoch comparable at all.
    def "epochs order by their token"() {
        expect:
        new ClaimEpoch(1) < new ClaimEpoch(2)
        new ClaimEpoch(2) == new ClaimEpoch(2)
        new ClaimEpoch(3) > new ClaimEpoch(2)
    }

    // NFR-S1: a token is a counter; a negative one is a programming error, not a legal epoch.
    def "a negative token is rejected"() {
        when:
        new ClaimEpoch(-1)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('-1')
    }
}
