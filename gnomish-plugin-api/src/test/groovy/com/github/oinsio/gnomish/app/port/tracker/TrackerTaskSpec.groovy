package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * TrackerTask: the full fact set returned by fetchTask — ref, snapshot, state
 * and abortFacts (tracker-port spec, "Full fact set for a working task").
 * Implements FR1 of add-tracker-port.
 */
class TrackerTaskSpec extends Specification {

    // FR1: fetchTask on a working task with recorded aborts carries the full fact set
    def "exposes ref, snapshot, state and abortFacts for a working task"() {
        given:
        def ref = new TaskRef('github:owner/repo#42')
        def snapshot = new TaskSnapshot('github:owner/repo#42', 'Fix the thing', 'body')
        def state = new TrackerTaskState.Working('instance-a')
        def facts = new AbortFacts(2, null)

        when:
        def task = new TrackerTask(ref, snapshot, state, facts, false)

        then:
        task.ref() == ref
        task.snapshot() == snapshot
        task.state() == state
        task.abortFacts() == facts
    }

    // FR1: a closed or nonexistent task is reported as Gone, not as an exception
    def "reports a closed task as Gone state, not an exception"() {
        given:
        def ref = new TaskRef('github:owner/repo#42')
        def snapshot = new TaskSnapshot('github:owner/repo#42', 'Fix the thing', 'body')

        when:
        def task = new TrackerTask(ref, snapshot, new TrackerTaskState.Gone(), AbortFacts.none(), false)

        then:
        task.state() == new TrackerTaskState.Gone()
    }

    // FR1: tracker tasks are values — equal content means equal facts
    def "tasks with the same components are equal values"() {
        given:
        def ref = new TaskRef('github:owner/repo#42')
        def snapshot = new TaskSnapshot('github:owner/repo#42', 'Fix the thing', 'body')
        def state = new TrackerTaskState.Ready()

        expect:
        new TrackerTask(ref, snapshot, state, AbortFacts.none(), false) ==
                new TrackerTask(ref, snapshot, state, AbortFacts.none(), false)

        and: 'a differing state makes them unequal'
        new TrackerTask(ref, snapshot, state, AbortFacts.none(), false) !=
                new TrackerTask(ref, snapshot, new TrackerTaskState.Finished(), AbortFacts.none(), false)
    }
}
