package com.github.oinsio.gnomish.adapter.agent.runner

import java.time.Instant
import spock.lang.Specification

/**
 * {@link RetainedEvents}, the events an agent round keeps within its retained cap (FR1, FR8, NFR-R1 of
 * extract-agent-round-runner): the opening half keeps the first lines whole, the latest half drops its
 * oldest events while over budget, a line with no events costs nothing, and the view reads both halves
 * in arrival order, however many events were dropped and removed.
 */
class RetainedEventsSpec extends Specification {

    static final Instant AT = Instant.parse('2026-11-20T10:00:00Z')

    def "NFR-R1: within the cap every event is kept, in order, each stamped"() {
        given:
        def retained = new RetainedEvents<String>(100)

        when:
        retained.add(['a', 'b'], AT, 10)
        retained.add([], AT, 1000)
        retained.add(['c'], AT.plusSeconds(1), 10)

        then:
        retained.view().stamped()*.event() == ['a', 'b', 'c']
        retained.view().stamped()*.at() == [AT, AT, AT.plusSeconds(1)]
        retained.dropped() == 0
    }

    def "NFR-R1: the opening half fills to its budget exactly"() {
        given: 'a cap of 20: ten bytes of opening, ten of latest'
        def retained = new RetainedEvents<String>(20)

        when: 'o2 fills the opening to its budget, so l1 alone is in the latest half and fits it'
        retained.add(['o1'], AT, 4)
        retained.add(['o2'], AT, 6)
        retained.add(['l1'], AT, 7)

        then:
        retained.view().stamped()*.event() == ['o1', 'o2', 'l1']
        retained.dropped() == 0
    }

    def "NFR-R1: a line that does not fit the opening closes it for good, so the order of arrival is kept"() {
        given:
        def retained = new RetainedEvents<String>(20)

        when: 'l1 does not fit beside o1 and closes the opening; l2 would fit there, and goes latest'
        retained.add(['o1'], AT, 4)
        retained.add(['l1'], AT, 7)
        retained.add(['l2'], AT, 1)

        then:
        retained.view().stamped()*.event() == ['o1', 'l1', 'l2']
    }

    def "NFR-R1: the latest half drops its oldest events while over its budget, keeping the opening"() {
        given:
        def retained = new RetainedEvents<String>(20)
        retained.add(['open'], AT, 10)

        when: 'six lines of four bytes after the opening: ten bytes of latest keep two of them'
        (1..6).each { retained.add(["l$it", "l$it-tail"], AT, 4) }

        then:
        retained.view().stamped()*.event() == [
            'open',
            'l5',
            'l5-tail',
            'l6',
            'l6-tail'
        ]
        retained.dropped() == 8
    }

    def "NFR-R1: thousands of lines past the cap leave the view exact, with indexes past its end refused"() {
        given:
        def retained = new RetainedEvents<Integer>(40)
        retained.add([-1], AT, 20)

        when:
        (0..<5000).each { retained.add([it], AT, 5) }

        then:
        def view = retained.view().stamped()
        view*.event() == [-1, 4996, 4997, 4998, 4999]
        view.size() == 5
        retained.dropped() == 4996

        when:
        view.get(5)

        then:
        thrown(IndexOutOfBoundsException)
    }

    def "FR2: the view is one window the dialect reads as lines arrive, never a copy"() {
        given:
        def retained = new RetainedEvents<String>(100)
        def view = retained.view()

        when:
        retained.add(['a'], AT, 1)

        then:
        view.stamped()*.event() == ['a']
        retained.view().is(view)
    }
}
