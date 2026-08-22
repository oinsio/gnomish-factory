package com.github.oinsio.gnomish.serveobservability

import java.time.Instant
import spock.lang.Specification

/**
 * {@link SweepVital}, task 6.1 of add-serve-sandbox-lifecycle (NFR-O1): the bounded inventory's
 * truncation must be STATED, not implied, so a reader can tell "two kept environments" from
 * "twenty of thirty-four shown".
 */
class SweepVitalSpec extends Specification {

    static final Instant TICK_AT = Instant.parse('2026-08-06T09:00:00Z')

    private static SweepVital vital(List<KeptEnvironmentEntry> kept, int keptTotal) {
        new SweepVital(TICK_AT, 300L, SweepCounts.NONE, kept, keptTotal, 0)
    }

    // NFR-O1: "the inventory SHALL be bounded in size, truncation stated in the snapshot".
    def "keptTruncated is true exactly when the total exceeds the carried entries"() {
        expect:
        vital(kept, keptTotal).keptTruncated() == truncated

        where:
        kept | keptTotal | truncated
        [] | 0 | false
        [
            new KeptEnvironmentEntry('task-1', 1L, 1L)
        ] | 1 | false
        [
            new KeptEnvironmentEntry('task-1', 1L, 1L)
        ] | 2 | true
    }

    def "the kept list is copied defensively"() {
        given:
        def mutable = [
            new KeptEnvironmentEntry('task-1', 1L, 1L)
        ]
        def vital = vital(mutable, 1)

        when:
        mutable.clear()

        then:
        vital.kept().size() == 1
    }

    def "a negative counter is rejected, naming the component"() {
        when:
        new SweepVital(TICK_AT, 300L, SweepCounts.NONE, [], keptTotal, consecutiveSkippedTicks)

        then:
        def error = thrown(IllegalArgumentException)
        error.message == "SweepVital.${component} must not be negative"

        where:
        keptTotal | consecutiveSkippedTicks | component
        -1 | 0 | 'keptTotal'
        0 | -1 | 'consecutiveSkippedTicks'
    }
}
