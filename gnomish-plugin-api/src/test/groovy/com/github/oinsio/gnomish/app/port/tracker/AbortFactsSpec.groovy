package com.github.oinsio.gnomish.app.port.tracker

import java.time.Instant
import spock.lang.Specification

/**
 * AbortFacts: abort history reconstructable by any instance from the tracker
 * alone (NFR-R3, design D10). Implements FR14 of add-tracker-port.
 */
class AbortFactsSpec extends Specification {

    // FR14: count and lastAbortAt round-trip exactly as constructed
    def "exposes count and lastAbortAt exactly as constructed"() {
        given:
        def when = Instant.parse('2026-07-16T10:15:30Z')

        when:
        def facts = new AbortFacts(2, when)

        then:
        facts.count() == 2
        facts.lastAbortAt() == when
    }

    // FR14: the initial state of a freshly ready task has no abort history
    def "none() reports zero count and no last-abort time"() {
        expect:
        AbortFacts.none() == new AbortFacts(0, null)
    }

    // FR14: an abort tally cannot be negative
    def "negative count is rejected with the component name in the message"() {
        when:
        new AbortFacts(-1, null)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AbortFacts.count')
    }

    // FR14: a zero count with no lastAbortAt is a structurally valid, common case
    def "zero count with null lastAbortAt is accepted"() {
        expect:
        new AbortFacts(0, null).lastAbortAt() == null
    }

    // FR14: abort facts are values — equal content means equal facts
    def "facts with the same components are equal values"() {
        given:
        def when = Instant.parse('2026-07-16T10:15:30Z')

        expect:
        new AbortFacts(2, when) == new AbortFacts(2, when)

        and: 'a differing count makes them unequal'
        new AbortFacts(1, when) != new AbortFacts(2, when)
    }
}
