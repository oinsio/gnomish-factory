package com.github.oinsio.gnomish.domain.pipeline

import spock.lang.Specification

/**
 * Threshold: the verdict threshold's wire vocabulary (NFR-R3, FR7 of
 * migrate-verify-to-executor-contract) — every constant has one token, every token reads back to
 * its constant, and a token outside the set reads as nothing, so the reader reports it.
 */
class ThresholdSpec extends Specification {

    // NFR-R3: the round trip covers every constant, so a constant added on one side only fails here
    def "NFR-R3: #threshold reads back from its own wire token"() {
        expect:
        Threshold.fromWire(threshold.wire()) == Optional.of(threshold)

        where:
        threshold << Threshold.values()
    }

    // FR7: the tokens are the SARIF level names the manifest spells, error the default
    def "FR7: the wire tokens are error, warning and note, and error is the default"() {
        expect:
        Threshold.values()*.wire() == ['error', 'warning', 'note']
        Threshold.DEFAULT == Threshold.ERROR
    }

    // NFR-R3: an unknown token, a constant's Java name and a differently cased token read as nothing
    def "NFR-R3: the token '#token' names no threshold"() {
        expect:
        Threshold.fromWire(token) == Optional.empty()

        where:
        token << [
            '',
            'none',
            'ERROR',
            'Warning',
            'fatal'
        ]
    }
}
