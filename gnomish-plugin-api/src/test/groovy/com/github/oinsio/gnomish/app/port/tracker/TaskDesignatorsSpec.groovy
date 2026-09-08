package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * TaskDesignators: the per-kind designator facts a task carries, where a kind
 * nobody extracted reads as absent rather than as a missing entry
 * (tracker-port spec, "Absent is absent, not empty"). Implements FR3 of
 * add-base-ref-resolution.
 */
class TaskDesignatorsSpec extends Specification {

    // FR3: a task with no designator facts at all reads as absent for every kind asked about
    def "an empty fact set reads as absent for any kind"() {
        expect:
        TaskDesignators.none().forKind('base') == new Designator.Absent()
        TaskDesignators.none().forKind('type') == new Designator.Absent()
    }

    // FR3: a kind the adapter did extract reads back exactly as classified
    def "a recorded kind reads back its own shape"() {
        given:
        def designators = TaskDesignators.of('base', Designator.single('release/1.18'))

        expect:
        designators.forKind('base') == new Designator.Single('release/1.18')

        and: 'an unrecorded kind is still absent, not an error'
        designators.forKind('type') == new Designator.Absent()
    }

    // FR3: kinds are open names -- several coexist in one fact set
    def "several kinds coexist in one fact set"() {
        given:
        def designators = new TaskDesignators([
            base: Designator.single('release/1.18'),
            type: Designator.conflict(['bug', 'epic']),
        ])

        expect:
        designators.forKind('base') == new Designator.Single('release/1.18')
        designators.forKind('type') == new Designator.Conflict(['bug', 'epic'])
    }

    // FR3: the fact set is inert value data -- it cannot change under its reader
    def "the fact set copies the map it was built from"() {
        given:
        def byKind = [base: Designator.single('main')]
        def designators = new TaskDesignators(byKind)

        when:
        byKind.put('type', Designator.single('bug'))

        then:
        designators.forKind('type') == new Designator.Absent()
    }

    // FR3: equal content means equal facts
    def "fact sets with the same content are equal values"() {
        expect:
        TaskDesignators.of('base', Designator.single('main')) ==
                TaskDesignators.of('base', Designator.single('main'))

        and:
        TaskDesignators.of('base', Designator.single('main')) != TaskDesignators.none()
    }
}
