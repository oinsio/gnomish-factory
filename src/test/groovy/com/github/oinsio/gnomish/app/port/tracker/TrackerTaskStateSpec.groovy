package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * TrackerTaskState: the logical task-state dictionary — Ready, Working(holder),
 * AwaitingHuman(reason), Finished, Gone — with transitions initiated only by
 * the factory or a human (FR2). Implements FR2 of add-tracker-port.
 */
class TrackerTaskStateSpec extends Specification {

    // FR2: Working exposes the claiming instance's holder identifier
    def "Working exposes its holder exactly as constructed"() {
        expect:
        new TrackerTaskState.Working('gnomish-factory-x7k2q1').holder() == 'gnomish-factory-x7k2q1'
    }

    // FR2: a working task with no claim holder cannot be reported correctly
    def "Working rejects a blank holder with the component named"() {
        when:
        new TrackerTaskState.Working(holder)

        then:
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('TrackerTaskState.Working.holder')

        where:
        holder << ['', '   ', '\t', ' \n']
    }

    // FR2: AwaitingHuman exposes the reason it was parked
    def "AwaitingHuman exposes its reason exactly as constructed"() {
        expect:
        new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION).reason() == ParkReason.ESCALATION
    }

    // FR2: the dictionary is sealed — an exhaustive switch handles all five variants
    def "an exhaustive switch over TrackerTaskState handles all five variants"() {
        expect:
        describe(state) == expected

        where:
        state                                                            | expected
        new TrackerTaskState.Ready()                                     | 'ready'
        new TrackerTaskState.Working('gnomish-factory-x7k2q1')           | 'working: gnomish-factory-x7k2q1'
        new TrackerTaskState.AwaitingHuman(ParkReason.CHECKPOINT)         | 'awaiting: CHECKPOINT'
        new TrackerTaskState.Finished()                                  | 'finished'
        new TrackerTaskState.Gone()                                      | 'gone'
    }

    // FR2: states are values — equal content means equal states
    def "states with the same components are equal values"() {
        expect:
        new TrackerTaskState.Working('a') == new TrackerTaskState.Working('a')
        new TrackerTaskState.Working('a') != new TrackerTaskState.Working('b')
        new TrackerTaskState.AwaitingHuman(ParkReason.INFRA) == new TrackerTaskState.AwaitingHuman(ParkReason.INFRA)
        new TrackerTaskState.AwaitingHuman(ParkReason.INFRA) != new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION)
        new TrackerTaskState.Ready() == new TrackerTaskState.Ready()
        new TrackerTaskState.Finished() == new TrackerTaskState.Finished()
        new TrackerTaskState.Gone() == new TrackerTaskState.Gone()
    }

    private static String describe(TrackerTaskState state) {
        switch (state) {
            case TrackerTaskState.Ready: return 'ready'
            case TrackerTaskState.Working: return 'working: ' + ((TrackerTaskState.Working) state).holder()
            case TrackerTaskState.AwaitingHuman: return 'awaiting: ' + ((TrackerTaskState.AwaitingHuman) state).reason()
            case TrackerTaskState.Finished: return 'finished'
            case TrackerTaskState.Gone: return 'gone'
            default: throw new IllegalStateException('unreachable')
        }
    }
}
