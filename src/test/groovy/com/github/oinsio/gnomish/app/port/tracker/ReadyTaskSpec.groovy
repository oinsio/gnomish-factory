package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * ReadyTask: one listReady entry — a TaskRef paired with AbortFacts, in
 * adapter queue order (design D1 sketch). Implements FR1, FR10 of
 * add-tracker-port.
 */
class ReadyTaskSpec extends Specification {

    // FR1, FR10: ref and abortFacts round-trip exactly as constructed
    def "exposes ref and abortFacts exactly as constructed"() {
        given:
        def ref = new TaskRef('github:owner/repo#42')
        def facts = new AbortFacts(1, null)

        when:
        def readyTask = new ReadyTask(ref, facts)

        then:
        readyTask.ref() == ref
        readyTask.abortFacts() == facts
    }

    // FR1: ready tasks are values — equal content means equal entries
    def "entries with the same components are equal values"() {
        given:
        def ref = new TaskRef('github:owner/repo#42')

        expect:
        new ReadyTask(ref, AbortFacts.none()) == new ReadyTask(ref, AbortFacts.none())

        and: 'a differing abortFacts makes them unequal'
        new ReadyTask(ref, AbortFacts.none()) != new ReadyTask(ref, new AbortFacts(1, null))
    }
}
