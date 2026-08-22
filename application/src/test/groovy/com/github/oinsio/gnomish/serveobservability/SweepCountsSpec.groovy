package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepVerdictCategory
import spock.lang.Specification

/**
 * {@link SweepCounts}, task 6.1 of add-serve-sandbox-lifecycle (NFR-O1, NFR-O2): the projection
 * from the tick log's open tally onto the six named fields the snapshot and the ledger both carry.
 */
class SweepCountsSpec extends Specification {

    def "NONE is all zeroes — a tick that evaluated no object"() {
        expect:
        SweepCounts.NONE == new SweepCounts(0, 0, 0, 0, 0, 0)
    }

    // NFR-O1: every category lands in its own field, with no cross-talk.
    def "of maps each category onto its own field"() {
        expect:
        SweepCounts.of([(category): 7]) == expected

        where:
        category | expected
        SweepVerdictCategory.CHECKED_ALIVE | new SweepCounts(7, 0, 0, 0, 0, 0)
        SweepVerdictCategory.KEPT_UNDER_THRESHOLD | new SweepCounts(0, 7, 0, 0, 0, 0)
        SweepVerdictCategory.STOPPED_ORPHAN | new SweepCounts(0, 0, 7, 0, 0, 0)
        SweepVerdictCategory.DISPOSED_AGED | new SweepCounts(0, 0, 0, 7, 0, 0)
        SweepVerdictCategory.DISPOSED_RECONSTRUCTIBLE| new SweepCounts(0, 0, 0, 0, 7, 0)
        SweepVerdictCategory.SKIPPED_NO_VERDICT | new SweepCounts(0, 0, 0, 0, 0, 7)
    }

    // NFR-O1: a category the tick never reached counts zero, not "absent".
    def "of defaults every absent category to zero"() {
        expect:
        SweepCounts.of([:]) == SweepCounts.NONE
    }

    def "of carries several categories at once"() {
        expect:
        SweepCounts.of([
            (SweepVerdictCategory.CHECKED_ALIVE): 3,
            (SweepVerdictCategory.DISPOSED_AGED): 2
        ]) == new SweepCounts(3, 0, 0, 2, 0, 0)
    }

    def "a negative count is rejected, naming the component"() {
        when:
        new SweepCounts(a, b, c, d, e, f)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "SweepCounts.${component} must not be negative"

        where:
        a | b | c | d | e | f | component
        -1 | 0 | 0 | 0 | 0 | 0 | 'checkedAlive'
        0 | -1 | 0 | 0 | 0 | 0 | 'keptUnderThreshold'
        0 | 0 | -1 | 0 | 0 | 0 | 'stoppedOrphan'
        0 | 0 | 0 | -1 | 0 | 0 | 'disposedAged'
        0 | 0 | 0 | 0 | -1 | 0 | 'disposedReconstructible'
        0 | 0 | 0 | 0 | 0 | -1 | 'skippedNoVerdict'
    }
}
