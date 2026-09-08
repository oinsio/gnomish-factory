package com.github.oinsio.gnomish.app.port.tracker

import spock.lang.Specification

/**
 * Designator: the three shapes a task's per-kind selection can take, and the
 * ONE classification function every adapter routes its candidates through
 * (tracker-port spec, "Designators are classified once and derived per
 * adapter"). Implements FR3 of add-base-ref-resolution.
 */
class DesignatorSpec extends Specification {

    // FR3: no candidates is the absent shape -- never an empty string, never a default
    def "no candidates classify as absent"() {
        expect:
        Designator.classify([]) == new Designator.Absent()
    }

    // FR3: one distinct candidate is the single shape, carrying the value verbatim
    def "one candidate classifies as single, carrying the value verbatim"() {
        when:
        def designator = Designator.classify(['release/1.18'])

        then:
        designator == new Designator.Single('release/1.18')
        (designator as Designator.Single).value() == 'release/1.18'
    }

    // FR3: equal duplicates collapse -- the same value found twice is one selection, not a conflict
    def "equal duplicates collapse to the single shape"() {
        expect:
        Designator.classify([
            'release/1.18',
            'release/1.18'
        ]) == new Designator.Single('release/1.18')
    }

    // FR3: several distinct values are a conflict listing every one, in the order reported
    def "distinct candidates classify as a conflict listing every value in report order"() {
        when:
        def designator = Designator.classify([
            'release/1.19',
            'release/1.18'
        ])

        then:
        designator == new Designator.Conflict([
            'release/1.19',
            'release/1.18'
        ])
    }

    // FR3: a conflict that also repeats a value still collapses the duplicates it holds
    def "a conflict keeps every distinct value once, in first-seen order"() {
        expect:
        Designator.classify(['a', 'b', 'a']) == new Designator.Conflict(['a', 'b'])
    }

    // FR3: the classified values are inert -- a conflict cannot change under its reader
    def "a conflict copies the values it was built from"() {
        given:
        def values = ['a', 'b']
        def designator = Designator.classify(values)

        when:
        values.add('c')

        then:
        designator == new Designator.Conflict(['a', 'b'])
    }

    // FR3: and the copy it holds is unmodifiable, so no reader can edit the fact
    def "a conflict refuses in-place mutation of its values"() {
        given:
        def designator = Designator.conflict(['a', 'b'])

        when:
        ((Designator.Conflict) designator).values().add('c')

        then:
        thrown(UnsupportedOperationException)
    }

    // FR3: the static factories name the same three shapes the classifier produces
    def "the shape factories build the same values as the classifier"() {
        expect:
        Designator.absent() == new Designator.Absent()
        Designator.single('x') == new Designator.Single('x')
        Designator.conflict(['x', 'y']) == new Designator.Conflict(['x', 'y'])
    }

    // FR3: a single designator with no value names nothing -- rejected at construction
    def "a single designator refuses a null value"() {
        when:
        new Designator.Single(null)

        then:
        thrown(NullPointerException)
    }
}
