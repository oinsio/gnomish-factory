package com.github.oinsio.gnomish.serveobservability

import com.github.oinsio.gnomish.domain.engine.TokenUsage
import spock.lang.Specification

/**
 * {@link LedgerTokenUsage}: the four token counts for one model within a ledger record's
 * {@code tokensByModel} map — inert value data, each field validated non-negative
 * independently via the shared {@code requireNonNegative} helper (kept out of the compact
 * constructor so PIT's record-constructor mutation suppression cannot exempt it from the
 * 100% gate).
 *
 * <p>Implements FR11, FR13 of add-serve-observability.
 */
class LedgerTokenUsageSpec extends Specification {

    def "exposes all four counts as constructed"() {
        when:
        def usage = new LedgerTokenUsage(100L, 50L, 10L, 5L)

        then:
        usage.input() == 100L
        usage.output() == 50L
        usage.cacheCreation() == 10L
        usage.cacheRead() == 5L
    }

    def "accepts all-zero counts"() {
        when:
        def usage = new LedgerTokenUsage(0L, 0L, 0L, 0L)

        then:
        usage.input() == 0L
        usage.output() == 0L
        usage.cacheCreation() == 0L
        usage.cacheRead() == 0L
    }

    def "rejects a negative #component count with the component named"() {
        when:
        new LedgerTokenUsage(input, output, cacheCreation, cacheRead)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains("LedgerTokenUsage.${component}")

        where:
        component        | input | output | cacheCreation | cacheRead
        'input'           | -1L   | 0L     | 0L            | 0L
        'output'          | 0L    | -1L    | 0L            | 0L
        'cacheCreation'   | 0L    | 0L     | -1L           | 0L
        'cacheRead'       | 0L    | 0L     | 0L            | -1L
    }

    def "of() converts an engine TokenUsage field-for-field"() {
        given:
        def usage = new TokenUsage(100L, 50L, 10L, 5L)

        when:
        def ledgerUsage = LedgerTokenUsage.of(usage)

        then:
        ledgerUsage.input() == 100L
        ledgerUsage.output() == 50L
        ledgerUsage.cacheCreation() == 10L
        ledgerUsage.cacheRead() == 5L
    }

    def "is value-equal by content"() {
        expect:
        new LedgerTokenUsage(1L, 2L, 3L, 4L) == new LedgerTokenUsage(1L, 2L, 3L, 4L)

        and:
        new LedgerTokenUsage(1L, 2L, 3L, 4L) != new LedgerTokenUsage(1L, 2L, 3L, 5L)
    }
}
