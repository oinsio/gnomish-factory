package com.github.oinsio.gnomish.app.base

import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators
import com.github.oinsio.gnomish.baseref.BaseDesignator
import com.github.oinsio.gnomish.untrustedtext.Provenance
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import spock.lang.Specification

/**
 * BaseDesignatorMapping (design D5 of add-base-ref-resolution): the port's
 * designator fact for kind {@code base} becomes the resolution policy's own
 * input value, shape for shape, deciding nothing on the way.
 *
 * <p>It is also the {@code TRACKER} mint for designator values (design D3 of type-untrusted-text,
 * task 6.1): the port's {@code Designator} is a published contract over open kinds, most of which
 * a reader routes on, so the carrier starts where the kind stops being open.
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
        'single' | Designator.single('release/1.18') ||
                BaseDesignator.single(UntrustedText.tracker('release/1.18'))
        'conflict' | Designator.conflict(['a', 'b']) ||
        BaseDesignator.conflict([
            UntrustedText.tracker('a'),
            UntrustedText.tracker('b')
        ])
    }

    // D3 of type-untrusted-text: the mapping is the mint, so what the policy receives says where
    //     it came from — every value, not only the single shape.
    def "every mapped value carries the tracker provenance"() {
        given:
        def single = BaseDesignatorMapping.baseOf(TaskDesignators.of('base', Designator.single('main')))
        def conflict = BaseDesignatorMapping.baseOf(TaskDesignators.of('base', Designator.conflict(['a', 'b'])))

        expect:
        single.value().provenance() == Provenance.TRACKER

        and:
        conflict.values().every { it.provenance() == Provenance.TRACKER }
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
