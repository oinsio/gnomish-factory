package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * ClaimResult: the outcome of a claim attempt — Acquired or Held(otherInstance)
 * (design D1 sketch). Implements FR1 of add-tracker-port.
 */
class ClaimResultSpec extends Specification {

    // FR1: Held exposes the identifier of the instance that won the claim
    def "Held exposes otherInstance exactly as constructed"() {
        expect:
        new ClaimResult.Held('gnomish-factory-x7k2q1').otherInstance() == 'gnomish-factory-x7k2q1'
    }

    // FR9, UX2: a lost claim must name the holder so the caller can refuse with a useful message
    def "Held rejects a blank otherInstance with the component named"() {
        when:
        new ClaimResult.Held(otherInstance)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('ClaimResult.Held.otherInstance')

        where:
        otherInstance << ['', '   ', '\t', ' \n']
    }

    // FR1: ClaimResult is sealed — an exhaustive switch handles both variants
    def "an exhaustive switch over ClaimResult handles both variants"() {
        expect:
        describe(result) == expected

        where:
        result | expected
        new ClaimResult.Acquired() | 'acquired'
        new ClaimResult.Held('gnomish-factory-x7k2') | 'held: gnomish-factory-x7k2'
    }

    // FR1: results are values — equal content means equal results
    def "results with the same components are equal values"() {
        expect:
        new ClaimResult.Acquired() == new ClaimResult.Acquired()
        new ClaimResult.Held('a') == new ClaimResult.Held('a')
        new ClaimResult.Held('a') != new ClaimResult.Held('b')
    }

    private static String describe(ClaimResult result) {
        switch (result) {
            case ClaimResult.Acquired: return 'acquired'
            case ClaimResult.Held: return 'held: ' + ((ClaimResult.Held) result).otherInstance()
            default: throw new IllegalStateException('unreachable')
        }
    }
}
