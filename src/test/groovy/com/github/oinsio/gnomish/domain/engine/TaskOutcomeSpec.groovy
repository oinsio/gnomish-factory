package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * TaskOutcome: the terminal result the engine returns for a task run — one of
 * {@code Completed}, {@code Paused(passedStage)}, {@code Escalated(report)} or
 * {@code Aborted(failedAt, cause)} — each carrying the final {@code TaskState}
 * exposed through the shared {@code finalState()} accessor so a caller can render
 * it from the outcome and its final state alone (UX1, design D1/D7). Implements
 * FR10 of add-stage-engine.
 */
class TaskOutcomeSpec extends Specification {

    private static TaskState sampleState() {
        TaskState.atStageStart('build')
    }

    private static AttemptKey sampleKey() {
        new AttemptKey('TASK-1', 'build', 0)
    }

    private static EscalationReport sampleReport() {
        new EscalationReport.AttemptsExhausted(3)
    }

    // FR10: Completed exposes its final state as constructed
    def "Completed exposes final state as constructed"() {
        given: 'a final state'
        def state = sampleState()

        when: 'a Completed outcome is created'
        def outcome = new TaskOutcome.Completed(state)

        then: 'the final state is exposed exactly as constructed'
        outcome.finalState() == state
    }

    // FR10: Paused exposes its final state and the stage that triggered the pause
    def "Paused exposes final state and passedStage as constructed"() {
        given: 'a final state'
        def state = sampleState()

        when: 'a Paused outcome is created'
        def outcome = new TaskOutcome.Paused(state, 'build')

        then: 'both components are exposed exactly as constructed'
        outcome.finalState() == state
        outcome.passedStage() == 'build'
    }

    // FR10: Escalated exposes its final state and the escalation report
    def "Escalated exposes final state and report as constructed"() {
        given: 'a final state and a report'
        def state = sampleState()
        def report = sampleReport()

        when: 'an Escalated outcome is created'
        def outcome = new TaskOutcome.Escalated(state, report)

        then: 'both components are exposed exactly as constructed'
        outcome.finalState() == state
        outcome.report() == report
    }

    // FR10: Aborted exposes its final state, the failing AttemptKey and the cause
    def "Aborted exposes final state, failedAt and cause as constructed"() {
        given: 'a final state and the failing attempt key'
        def state = sampleState()
        def key = sampleKey()

        when: 'an Aborted outcome is created'
        def outcome = new TaskOutcome.Aborted(state, key, 'disk full: java.io.IOException ...')

        then: 'each component is exposed exactly as constructed'
        outcome.finalState() == state
        outcome.failedAt() == key
        outcome.cause() == 'disk full: java.io.IOException ...'
    }

    // FR10: finalState() is reachable through the TaskOutcome interface for every variant
    def "finalState is reachable via the TaskOutcome interface for all four variants"() {
        expect: 'the interface accessor returns the same final state each variant was built with'
        outcome.finalState() == sampleState()

        where:
        outcome << [
            new TaskOutcome.Completed(sampleState()),
            new TaskOutcome.Paused(sampleState(), 'build'),
            new TaskOutcome.Escalated(sampleState(), sampleReport()),
            new TaskOutcome.Aborted(sampleState(), sampleKey(), 'boom')
        ]
    }

    // FR10: Paused requires a non-blank passedStage — a pause names the stage that passed
    def "Paused rejects a blank passedStage with the component named"() {
        when: 'a Paused is created with a blank passedStage'
        new TaskOutcome.Paused(sampleState(), passedStage)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('Paused.passedStage')

        where:
        passedStage << ['', '   ', '\t', ' \n']
    }

    // FR10: Aborted requires a non-blank cause — a persistence failure must describe itself
    def "Aborted rejects a blank cause with the component named"() {
        when: 'an Aborted is created with a blank cause'
        new TaskOutcome.Aborted(sampleState(), sampleKey(), cause)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('Aborted.cause')

        where:
        cause << ['', '   ', '\t', ' \n']
    }

    // FR10: TaskOutcome is sealed — an exhaustive switch handles all four variants
    def "an exhaustive switch over TaskOutcome handles all four variants"() {
        expect: 'each variant is matched to its own arm'
        describe(outcome) == expected

        where:
        outcome                                                             | expected
        new TaskOutcome.Completed(sampleState())                            | 'completed'
        new TaskOutcome.Paused(sampleState(), 'build')                      | 'paused: build'
        new TaskOutcome.Escalated(sampleState(), sampleReport())            | 'escalated'
        new TaskOutcome.Aborted(sampleState(), sampleKey(), 'boom')         | 'aborted: boom'
    }

    // FR10: Completed outcomes are values — equal content means equal outcomes
    def "Completed outcomes with the same components are equal values"() {
        expect: 'two independently constructed Completed outcomes with equal content are equal'
        new TaskOutcome.Completed(sampleState()) == new TaskOutcome.Completed(sampleState())
    }

    // FR10: Paused outcomes are values — equal content means equal outcomes
    def "Paused outcomes with the same components are equal values"() {
        expect: 'equal content is equal'
        new TaskOutcome.Paused(sampleState(), 'build') == new TaskOutcome.Paused(sampleState(), 'build')

        and: 'a differing passedStage makes them unequal'
        new TaskOutcome.Paused(sampleState(), 'build') != new TaskOutcome.Paused(sampleState(), 'test')
    }

    // FR10: Escalated outcomes are values — equal content means equal outcomes
    def "Escalated outcomes with the same components are equal values"() {
        expect: 'equal content is equal'
        new TaskOutcome.Escalated(sampleState(), sampleReport()) ==
                new TaskOutcome.Escalated(sampleState(), sampleReport())

        and: 'a differing report makes them unequal'
        new TaskOutcome.Escalated(sampleState(), new EscalationReport.AttemptsExhausted(1)) !=
                new TaskOutcome.Escalated(sampleState(), new EscalationReport.AttemptsExhausted(2))
    }

    // FR10: Aborted outcomes are values — equal content means equal outcomes
    def "Aborted outcomes with the same components are equal values"() {
        expect: 'equal content is equal'
        new TaskOutcome.Aborted(sampleState(), sampleKey(), 'boom') ==
                new TaskOutcome.Aborted(sampleState(), sampleKey(), 'boom')

        and: 'a differing cause makes them unequal'
        new TaskOutcome.Aborted(sampleState(), sampleKey(), 'a') !=
                new TaskOutcome.Aborted(sampleState(), sampleKey(), 'b')
    }

    private static String describe(TaskOutcome outcome) {
        switch (outcome) {
            case TaskOutcome.Completed: return 'completed'
            case TaskOutcome.Paused: return 'paused: ' + ((TaskOutcome.Paused) outcome).passedStage()
            case TaskOutcome.Escalated: return 'escalated'
            case TaskOutcome.Aborted: return 'aborted: ' + ((TaskOutcome.Aborted) outcome).cause()
            default: throw new IllegalStateException('unreachable')
        }
    }
}
