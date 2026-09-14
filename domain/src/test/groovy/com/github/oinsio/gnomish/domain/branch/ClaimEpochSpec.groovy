package com.github.oinsio.gnomish.domain.branch

import spock.lang.Specification

/**
 * FR13, NFR-S1 of harden-task-branch-contract, FR1 of fix-claim-epoch-fence: the claim epoch is an
 * opaque token the tracker issues and the writers stamp — no reader compares two of them — and it
 * carries a counter and nothing else.
 */
class ClaimEpochSpec extends Specification {

    // FR1 of fix-claim-epoch-fence: the epoch is identity, not an order to judge artifacts by —
    // two epochs are the same tenure or different ones, and that is the whole comparison.
    def "an epoch equals exactly the epoch with its token"() {
        expect:
        new ClaimEpoch(2) == new ClaimEpoch(2)
        new ClaimEpoch(3) != new ClaimEpoch(2)
    }

    // NFR-S1: the token is the whole value, so a stamp carries a counter and nothing else.
    def "an epoch carries its token and nothing else"() {
        expect:
        new ClaimEpoch(7).token() == 7L
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
