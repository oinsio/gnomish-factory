package com.github.oinsio.gnomish.app.sandboxlifecycle

import spock.lang.Specification

/**
 * {@link SweepVerdictFanout}, task 6.1/6.2 of add-serve-sandbox-lifecycle (FR9): the daemon needs
 * two sinks on one evaluator seam, and both must see every verdict, in order.
 */
class SweepVerdictFanoutSpec extends Specification {

    private static SweepVerdict verdict(String name) {
        new SweepVerdict(SweepVerdictCategory.CHECKED_ALIVE, name, 'main-box', 'tracked', 'task-1', 'reason', null)
    }

    // FR9: every sink sees every verdict.
    def "delivers each verdict to every sink"() {
        given:
        def first = []
        def second = []
        def fanout = new SweepVerdictFanout([
            { SweepVerdict v -> first << v } as SweepVerdictListener,
            { SweepVerdict v -> second << v } as SweepVerdictListener
        ])
        def a = verdict('a')
        def b = verdict('b')

        when:
        fanout.onVerdict(a)
        fanout.onVerdict(b)

        then:
        first == [a, b]
        second == [a, b]
    }

    // FR9: delivery follows the caller's own order, so a sink that logs before another writes
    //     stays in that relation for every verdict.
    def "delivers to the sinks in the order given"() {
        given:
        def order = []
        def fanout = new SweepVerdictFanout([
            { SweepVerdict v -> order << 'first' } as SweepVerdictListener,
            { SweepVerdict v -> order << 'second' } as SweepVerdictListener
        ])

        when:
        fanout.onVerdict(verdict('a'))

        then:
        order == ['first', 'second']
    }

    // FR9: no sink at all is legal — a caller that wires none is simply unobserved.
    def "an empty fanout swallows the verdict without failing"() {
        when:
        new SweepVerdictFanout([]).onVerdict(verdict('a'))

        then:
        noExceptionThrown()
    }
}
