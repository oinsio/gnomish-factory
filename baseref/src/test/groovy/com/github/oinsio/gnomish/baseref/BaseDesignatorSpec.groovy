package com.github.oinsio.gnomish.baseref

import spock.lang.Specification

/**
 * {@link BaseDesignator} held against the allowed bases: the designator tier of base-ref-resolution on its
 * own, before the priority order puts it in place.
 *
 * <p>FR3: the base a task named arrives already classified into one of three shapes — absent, a
 * single value, or a conflict listing every value found. Classifying tracker candidates into those
 * shapes happens beside the tracker port, not here; what this module owns is what each shape means
 * once the allowed bases are in hand.
 *
 * <p>FR4, UX2: a value the project does not allow and a conflict both refuse, and the refusal names what was
 * found and what the configuration allows.
 */
class BaseDesignatorSpec extends Specification {

    private static final AllowedBases ALLOWED = AllowedBases.of([
        AllowedBase.of(BasePattern.compile('main')),
        new AllowedBase(BasePattern.compile('release/*'), BranchRole.RELEASE),
    ])

    // FR3: the absent shape abstains — the ordinary case for a project routing everything to trunk
    def "an absent designator selects nothing"() {
        expect:
        BaseDesignator.absent().against(ALLOWED) instanceof DesignatorSelection.None

        and: 'and abstains when nothing is allowed just the same'
        BaseDesignator.absent().against(AllowedBases.empty()) instanceof DesignatorSelection.None

        and: 'absence carries no identity of its own'
        BaseDesignator.absent() == BaseDesignator.absent()
    }

    // FR4: an allowed selection is accepted, and the accepting entry comes back with it
    def "an allowed single value is accepted with the entry that accepted it"() {
        when:
        def selection = BaseDesignator.single('release/1.18').against(ALLOWED)

        then:
        selection instanceof DesignatorSelection.Accepted
        selection.refName() == 'release/1.18'
        selection.entry().pattern().source() == 'release/*'
        selection.entry().role() == BranchRole.RELEASE
    }

    // FR4, UX2: a value that is not allowed refuses, naming it and the allowed bases that rejected it
    def "a single value that is not allowed is refused: '#value'"() {
        when:
        def selection = BaseDesignator.single(value).against(ALLOWED)

        then:
        selection instanceof DesignatorSelection.Refused
        selection.cause() == UnderdeterminedCause.DESIGNATOR_NOT_ALLOWED
        selection.values() == [value]
        selection.reason() == "the task names base '${value}', which no configured allowed base " +
                'accepts; the allowed bases are main, release/*'

        where:
        value << [
            'experiments/foo',
            'develop',
            'release/../../etc/passwd',
            'release/*'
        ]
    }

    // base-ref-resolution: "No allowed base with a designator is refused, not resolved" — the defensive
    // arm that keeps the runtime fail-closed if the load-time check (pipeline-config) is bypassed
    def "a value reaching an empty list of allowed bases is refused rather than branched from"() {
        when:
        def selection = BaseDesignator.single('release/1.18').against(AllowedBases.empty())

        then:
        selection instanceof DesignatorSelection.Refused
        selection.cause() == UnderdeterminedCause.DESIGNATOR_NOT_ALLOWED
        selection.reason().endsWith('the allowed bases are (empty)')
    }

    // FR4, UX2: two labels park the task with both values — resolution never picks one
    def "a conflict is refused with every value found, in the order they were reported"() {
        when:
        def selection = BaseDesignator.conflict([
            'release/1.18',
            'release/1.19'
        ]).against(ALLOWED)

        then:
        selection instanceof DesignatorSelection.Refused
        selection.cause() == UnderdeterminedCause.DESIGNATOR_CONFLICT
        selection.values() == [
            'release/1.18',
            'release/1.19'
        ]
        selection.reason() == 'the task names more than one base (release/1.18, release/1.19); ' +
                'resolution never picks one — the allowed bases are main, release/*'
    }

    // FR4: a conflict refuses even when every value is allowed — the ambiguity is the fault
    def "a conflict of allowed values still refuses"() {
        expect:
        BaseDesignator.conflict(['main', 'release/1.18']).against(ALLOWED).cause()
        == UnderdeterminedCause.DESIGNATOR_CONFLICT

        and: 'and refuses identically when nothing is allowed, naming it'
        BaseDesignator.conflict(['main', 'release/1.18']).against(AllowedBases.empty()).reason()
        .endsWith('the allowed bases are (empty)')
    }

    def "the designator shapes are values that copy what they are given"() {
        given:
        def values = [
            'release/1.18',
            'release/1.19'
        ]
        def designator = BaseDesignator.conflict(values)

        when:
        values.add('release/1.20')

        then:
        designator.values() == [
            'release/1.18',
            'release/1.19'
        ]

        and: 'equal shapes compare equal, so a mapped designator can be asserted as a value'
        BaseDesignator.single('main') == BaseDesignator.single('main')
        BaseDesignator.single('main') != BaseDesignator.single('release/1.18')
        BaseDesignator.conflict(['a', 'b']) == BaseDesignator.conflict(['a', 'b'])
    }

    def "the designator shapes refuse missing values"() {
        when:
        BaseDesignator.single(null)

        then:
        thrown(NullPointerException)

        when:
        BaseDesignator.conflict(null)

        then:
        thrown(NullPointerException)
    }

    def "a refusal copies its values and refuses missing components"() {
        given:
        def values = ['a', 'b']
        def refused = new DesignatorSelection.Refused(
                UnderdeterminedCause.DESIGNATOR_CONFLICT, values, 'because')

        when:
        values.add('c')

        then:
        refused.values() == ['a', 'b']

        when:
        new DesignatorSelection.Refused(cause, ['a'], reason)

        then:
        thrown(NullPointerException)

        where:
        cause | reason
        null | 'because'
        UnderdeterminedCause.DESIGNATOR_CONFLICT | null
    }

    def "an accepted selection refuses missing components"() {
        when:
        new DesignatorSelection.Accepted(refName, entry)

        then:
        thrown(NullPointerException)

        where:
        refName | entry
        null | AllowedBase.of(BasePattern.compile('main'))
        'main' | null
    }
}
