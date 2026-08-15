package com.github.oinsio.gnomish.serveobservability

import spock.lang.Specification

/**
 * {@link OutcomeCounts}: a {@code runSummary} line's four named outcome counters (FR13) —
 * inert value data, each field validated non-negative independently via the shared
 * {@code requireNonNegative} helper (kept out of the compact constructor so PIT's
 * record-constructor mutation suppression cannot exempt it from the 100% gate).
 *
 * <p>Implements FR13 of add-serve-observability.
 */
class OutcomeCountsSpec extends Specification {

    def "exposes all four counts as constructed"() {
        when:
        def counts = new OutcomeCounts(1, 2, 3, 4)

        then:
        counts.delivered() == 1
        counts.awaitingHuman() == 2
        counts.aborted() == 3
        counts.revoked() == 4
    }

    def "accepts all-zero counts"() {
        when:
        def counts = new OutcomeCounts(0, 0, 0, 0)

        then:
        counts.delivered() == 0
        counts.awaitingHuman() == 0
        counts.aborted() == 0
        counts.revoked() == 0
    }

    def "rejects a negative #component count with the component named"() {
        when:
        new OutcomeCounts(delivered, awaitingHuman, aborted, revoked)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("OutcomeCounts.${component}")

        where:
        component | delivered | awaitingHuman | aborted | revoked
        'delivered' | -1 | 0 | 0 | 0
        'awaitingHuman' | 0 | -1 | 0 | 0
        'aborted' | 0 | 0 | -1 | 0
        'revoked' | 0 | 0 | 0 | -1
    }

    def "is value-equal by content"() {
        expect:
        new OutcomeCounts(1, 2, 3, 4) == new OutcomeCounts(1, 2, 3, 4)

        and:
        new OutcomeCounts(1, 2, 3, 4) != new OutcomeCounts(1, 2, 3, 5)
    }
}
