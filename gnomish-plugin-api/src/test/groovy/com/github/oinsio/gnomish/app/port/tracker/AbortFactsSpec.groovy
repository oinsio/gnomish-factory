package com.github.oinsio.gnomish.app.port.tracker

import java.time.Instant
import spock.lang.Specification

/**
 * AbortFacts: the unified automatic-retry accounting, reconstructable by any instance from the
 * tracker alone (NFR-R3, design D10). Implements FR14 of add-tracker-port; the crash/recovery
 * categorization is FR14 of harden-task-branch-contract.
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

    // FR14 of harden-task-branch-contract: one counter, two categories — the recovery share is
    // carried explicitly and the crash share is the rest of the total
    def "splits the one counter into the recovery share and the crash remainder"() {
        given:
        def when = Instant.parse('2026-07-16T10:15:30Z')

        when:
        def facts = new AbortFacts(5, when, 2)

        then:
        facts.count() == 5
        facts.recoveryCount() == 2
        facts.crashCount() == 3
    }

    // FR14 of harden-task-branch-contract: an attempt recorded before the categorization existed
    // means what every such marker meant — a crashed run
    def "facts constructed without a category read as all crashes"() {
        expect:
        new AbortFacts(3, null).recoveryCount() == 0
        new AbortFacts(3, null).crashCount() == 3
    }

    // FR14 of harden-task-branch-contract: the categories partition the counter, so a share
    // outside [0, count] names no possible history
    def "a recovery share of #share against a total of #total is rejected"() {
        when:
        new AbortFacts(total, null, share)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AbortFacts.recoveryCount')

        where:
        total | share
        2 | 3
        2 | -1
        0 | 1
    }

    // FR14: abort facts are values — equal content means equal facts
    def "facts with the same components are equal values"() {
        given:
        def when = Instant.parse('2026-07-16T10:15:30Z')

        expect:
        new AbortFacts(2, when) == new AbortFacts(2, when)

        and: 'a differing count makes them unequal'
        new AbortFacts(1, when) != new AbortFacts(2, when)

        and: 'so does a differing recovery share of the same total'
        new AbortFacts(2, when, 1) != new AbortFacts(2, when, 2)
    }
}
