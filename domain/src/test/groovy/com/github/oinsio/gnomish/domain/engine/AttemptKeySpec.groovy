package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * AttemptKey: the shared {@code (taskId, stage, attempt)} correlation key that
 * logs (UX2) and telemetry share, and that correlates a raw ToolTrace kept
 * outside TaskState back to its attempt (design D5). Contract: non-blank taskId
 * and stage, non-negative attempt. Implements FR13 of add-stage-engine.
 */
class AttemptKeySpec extends Specification {

    // FR13: an attempt key exposes its taskId, stage and attempt as constructed
    def "exposes taskId, stage and attempt as constructed"() {
        when: 'an attempt key is created'
        def key = new AttemptKey('TASK-42', 'build', 2)

        then: 'each component is exposed exactly as constructed'
        key.taskId() == 'TASK-42'
        key.stage() == 'build'
        key.attempt() == 2
    }

    // FR13: a validated attempt round-trips a specific non-trivial literal
    // (pins requireNonNegative's return against a return-value mutation)
    def "a validated attempt round-trips the constructed literal"() {
        expect: 'the accessor returns the exact non-zero attempt it was built with'
        new AttemptKey('TASK-1', 'build', 4).attempt() == 4
    }

    // FR13: attempt is a round sequence — the base round zero is accepted
    def "accepts attempt zero"() {
        when: 'an attempt key is created with the base attempt'
        def key = new AttemptKey('TASK-1', 'design', 0)

        then: 'the zero attempt is exposed as constructed'
        key.attempt() == 0
    }

    // FR13: taskId is the correlation key part — a blank taskId is rejected
    def "rejects a blank taskId with the component named"() {
        when: 'an attempt key is created with a blank taskId'
        new AttemptKey(taskId, 'build', 0)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AttemptKey.taskId')

        where:
        taskId << ['', '   ', '\t', ' \n']
    }

    // FR13: the stage names which stage the attempt belongs to — a blank stage is rejected
    def "rejects a blank stage with the component named"() {
        when: 'an attempt key is created with a blank stage'
        new AttemptKey('TASK-1', stage, 0)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AttemptKey.stage')

        where:
        stage << ['', '   ', '\t', ' \n']
    }

    // FR13: a round number cannot be negative — a negative attempt is rejected
    def "rejects a negative attempt with the component named"() {
        when: 'an attempt key is created with a negative attempt'
        new AttemptKey('TASK-1', 'build', attempt)

        then: 'construction fails and the message names the attempt component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('AttemptKey.attempt')

        where:
        attempt << [-1, -10, -100]
    }

    // FR13: AttemptKey is inert value data compared by content
    def "is value-equal by content"() {
        expect: 'two keys built from equal components are equal'
        new AttemptKey('TASK-1', 'build', 1) == new AttemptKey('TASK-1', 'build', 1)

        and: 'a differing attempt makes them unequal'
        new AttemptKey('TASK-1', 'build', 1) != new AttemptKey('TASK-1', 'build', 2)
    }
}
