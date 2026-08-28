package com.github.oinsio.gnomish.app.port.tracker

import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
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
        new ClaimResult.Acquired(new ClaimEpoch(1)) | 'acquired'
        new ClaimResult.Held('gnomish-factory-x7k2') | 'held: gnomish-factory-x7k2'
    }

    // FR13 of harden-task-branch-contract: an acquired claim carries the tenure's epoch,
    // the token the holder stamps into every commit and tracker write until the tenure ends
    def "Acquired exposes the epoch it was issued"() {
        expect:
        new ClaimResult.Acquired(new ClaimEpoch(4711)).epoch() == new ClaimEpoch(4711)
    }

    // FR13 of harden-task-branch-contract: two tenures of one task are distinguished by epoch alone
    def "two acquisitions differing only in epoch are different results"() {
        expect:
        new ClaimResult.Acquired(new ClaimEpoch(1)) != new ClaimResult.Acquired(new ClaimEpoch(2))
    }

    // FR1: results are values — equal content means equal results
    def "results with the same components are equal values"() {
        expect:
        new ClaimResult.Acquired(new ClaimEpoch(1)) == new ClaimResult.Acquired(new ClaimEpoch(1))
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
