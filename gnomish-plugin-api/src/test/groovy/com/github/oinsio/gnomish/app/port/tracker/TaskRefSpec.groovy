package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * TaskRef: a task's opaque canonical identity handed to and returned from the
 * Tracker port (design D1, FR16). Implements FR1, FR16 of add-tracker-port.
 */
class TaskRefSpec extends Specification {

    // FR1, FR16: the canonical id round-trips exactly as constructed
    def "exposes the canonical id exactly as constructed"() {
        expect:
        new TaskRef('github:owner/repo#42').id() == 'github:owner/repo#42'
    }

    // FR1, FR16: an opaque identity with no content cannot round-trip through the port
    def "blank id is rejected with the component name in the message"() {
        when:
        new TaskRef(id)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('TaskRef.id')

        where:
        id << ['', '   ', '\t', ' \n']
    }

    // FR1: TaskRef is a value — equal content means equal refs
    def "refs with the same id are equal values"() {
        expect:
        new TaskRef('github:owner/repo#42') == new TaskRef('github:owner/repo#42')

        and: 'a differing id makes them unequal'
        new TaskRef('github:owner/repo#42') != new TaskRef('github:owner/repo#43')
    }
}
