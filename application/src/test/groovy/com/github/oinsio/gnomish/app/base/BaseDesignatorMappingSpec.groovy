package com.github.oinsio.gnomish.app.base

import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators
import com.github.oinsio.gnomish.baseref.BaseDesignator
import spock.lang.Specification

/**
 * BaseDesignatorMapping (design D5 of add-base-ref-resolution): the port's
 * designator fact for kind {@code base} becomes the resolution policy's own
 * input value, shape for shape, deciding nothing on the way.
 *
 * Implements FR3 of add-base-ref-resolution.
 */
class BaseDesignatorMappingSpec extends Specification {

    // FR3: each of the three port shapes becomes the policy's matching shape, values intact
    def "maps the port shape onto the policy's own input value: #shape"() {
        expect:
        BaseDesignatorMapping.baseOf(TaskDesignators.of('base', ported)) == expected

        where:
        shape | ported || expected
        'absent' | Designator.absent() || BaseDesignator.absent()
        'single' | Designator.single('release/1.18') || BaseDesignator.single('release/1.18')
        'conflict' | Designator.conflict(['a', 'b']) || BaseDesignator.conflict(['a', 'b'])
    }

    // FR3: a task whose adapter extracts no base at all is absent, not an error and not a default
    def "a fact set with no base entry maps to absent"() {
        expect:
        BaseDesignatorMapping.baseOf(TaskDesignators.none()) == BaseDesignator.absent()
    }

    // FR3: only kind 'base' reaches the policy -- another kind's fact is not mistaken for it
    def "another kind's designator is not read as the base"() {
        given:
        def designators = TaskDesignators.of('type', Designator.single('bug'))

        expect:
        BaseDesignatorMapping.baseOf(designators) == BaseDesignator.absent()
    }
}
