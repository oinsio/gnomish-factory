package com.github.oinsio.gnomish.logtext

import spock.lang.Specification

/**
 * {@link OperatorEvent}: the operator-event catalog's own invariants. The catalog is a contract
 * only while its codes are well-formed, unique and rendered the one way every call site expects —
 * a duplicated code silently merges two faults under one alert, and a head missing its brackets
 * makes every grep keyed on {@code [GF} miss the line.
 *
 * <p>What this spec deliberately does not check is the correspondence between constants and call
 * sites (every site coded, every code used once, every code named by a test): that is a scan of
 * the source tree, and it lives in the static log-contract gate (FR16).
 *
 * <p>FR14 of harden-logging-observability.
 */
class OperatorEventSpec extends Specification {

    def "FR14: every code is a three-digit GF number"() {
        expect:
        OperatorEvent.values().every { it.code() ==~ /GF\d{3}/ }
    }

    def "FR14: no two events share a code"() {
        given:
        def codes = OperatorEvent.values().collect { it.code() }

        expect:
        codes.toUnique().size() == codes.size()
    }

    def "FR14: the head is the code in brackets followed by one space"() {
        expect:
        OperatorEvent.values().every { it.head() == "[${it.code()}] " }
    }

    def "FR14: the head prefixes a message without swallowing it"() {
        expect:
        OperatorEvent.SWEEP_LEDGER_APPEND_FAILED.head() + 'failed to append' ==
                '[GF106] failed to append'
    }

    def "FR14: the catalog is not empty — a mis-generated enum cannot pass the checks above"() {
        expect:
        OperatorEvent.values().length >= 125
    }
}
