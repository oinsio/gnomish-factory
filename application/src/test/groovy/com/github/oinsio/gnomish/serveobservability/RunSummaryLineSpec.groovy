package com.github.oinsio.gnomish.serveobservability

import java.time.Instant
import spock.lang.Specification

/**
 * {@link RunSummaryLine}: the ledger {@code runSummary} line's own validation (FR13) —
 * {@code wallMillis} must not be negative. Assembly from a {@link RunSummaryAccumulator} is
 * covered separately by {@code RunSummaryLineAssemblerSpec}; this spec exercises the record's
 * own compact-constructor guard directly.
 *
 * <p>Implements FR13 of add-serve-observability.
 */
class RunSummaryLineSpec extends Specification {

    private static final InstanceInfo INSTANCE = new InstanceInfo('gnomish-ab12cd', 'worker-1', '0.1.0')
    private static final Instant STARTED = Instant.parse('2026-08-03T10:00:00Z')
    private static final Instant FINISHED = Instant.parse('2026-08-03T10:05:00Z')
    private static final OutcomeCounts COUNTS = new OutcomeCounts(1, 0, 0, 0)

    def "rejects a negative wallMillis"() {
        when:
        new RunSummaryLine(INSTANCE, STARTED, FINISHED, -1L, COUNTS, [:])

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('RunSummaryLine.wallMillis')
    }

    def "accepts a zero wallMillis"() {
        when:
        def line = new RunSummaryLine(INSTANCE, STARTED, FINISHED, 0L, COUNTS, [:])

        then:
        line.wallMillis() == 0L
    }
}
