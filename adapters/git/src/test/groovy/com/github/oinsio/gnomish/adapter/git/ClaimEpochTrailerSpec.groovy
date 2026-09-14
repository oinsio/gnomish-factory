package com.github.oinsio.gnomish.adapter.git

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import spock.lang.Specification

/**
 * ClaimEpochTrailer: the claim epoch as a commit-message trailer — the branch's record of which
 * tenure wrote a commit (FR13 of harden-task-branch-contract, design D6). Write-only since
 * fix-claim-epoch-fence FR3: nothing reads the trailer back to classify a branch, so the stamp is
 * provenance for an operator reading the log, not an input to any decision.
 */
class ClaimEpochTrailerSpec extends Specification {

    // FR13: a stamped commit says which tenure wrote it
    def "stamps the epoch as a trailer below the service subject"() {
        expect:
        ClaimEpochTrailer.stamp('gnomish: round build#1', new ClaimEpoch(4711)) ==
                'gnomish: round build#1\n\nGnomish-Claim-Epoch: 4711'
    }

    // FR13: the subject stays verbatim — SnapshotTipCheck and the cleanup search match it exactly
    def "leaves the subject line untouched"() {
        when:
        def stamped = ClaimEpochTrailer.stamp(ServiceCommitMessages.cleanup(), new ClaimEpoch(9))

        then:
        stamped.readLines().first() == ServiceCommitMessages.cleanup()
    }

    // FR13: a writer holding no claim writes the message it always wrote
    def "stamps nothing when there is no tenure"() {
        expect:
        ClaimEpochTrailer.stamp('gnomish: salvage', null) == 'gnomish: salvage'
    }
}
