package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.Designator
import spock.lang.Specification

/**
 * GithubDesignatorRules (github-tracker spec, "Designator candidates are
 * extracted from issue labels by configured rule"): the compiled
 * {@code tracker.github.designators} map, the full-match label extraction it
 * drives, and the kinds it declares through the factory seam.
 *
 * Implements FR3 of add-base-ref-resolution.
 */
class GithubDesignatorRulesSpec extends Specification {

    private static GithubDesignatorRules rulesFor(Map<String, Object> designators) {
        GithubDesignatorRules.from(['api-url': 'https://api.github.test', designators: designators])
    }

    // FR3: one matching label yields one candidate, and the state label plays no part
    def "one matching label yields the single designator"() {
        given:
        def rules = rulesFor(base: 'base:(.+)')

        when:
        def facts = rules.extract([
            'base:release/1.18',
            'gnomish:ready'
        ])

        then:
        facts.forKind('base') == new Designator.Single('release/1.18')
    }

    // FR3: the rule matches a label in full -- a label merely containing the pattern is not a match
    def "partial matches do not count"() {
        given:
        def rules = rulesFor(base: 'base:(.+)')

        expect:
        rules.extract(['my-base:foo']).forKind('base') == new Designator.Absent()
    }

    // FR3: two captures with different values are a conflict, in the order the API returned the labels
    def "two labels with different values are a conflict in label order"() {
        given:
        def rules = rulesFor(base: 'base:(.+)')

        expect:
        rules.extract([
            'base:release/1.19',
            'base:release/1.18'
        ]).forKind('base') ==
        new Designator.Conflict([
            'release/1.19',
            'release/1.18'
        ])
    }

    // FR3: the same value twice is one selection -- the adapter classifies, it never resolves
    def "the same value on two labels collapses to one designator"() {
        given:
        def rules = rulesFor(base: 'base:(.+)')

        expect:
        rules.extract(['base:main', 'base:main']).forKind('base') == new Designator.Single('main')
    }

    // FR3: an issue with no matching label yields absent, never an empty string and never a default
    def "no matching label yields absent"() {
        given:
        def rules = rulesFor(base: 'base:(.+)')

        expect:
        rules.extract(['gnomish:ready']).forKind('base') == new Designator.Absent()
    }

    // FR3: kinds are open names -- several rules coexist and are extracted independently
    def "several declared kinds are extracted independently"() {
        given:
        def rules = rulesFor(base: 'base:(.+)', type: 'type:(.+)')

        when:
        def facts = rules.extract(['base:main', 'type:bug'])

        then:
        facts.forKind('base') == new Designator.Single('main')
        facts.forKind('type') == new Designator.Single('bug')

        and: 'and both kinds are declared through the factory seam'
        rules.kinds() == ['base', 'type'] as Set
    }

    // FR3: a kind with no rule is never extracted and never reported
    def "a subsection with no designators map declares no kinds and extracts nothing"() {
        given:
        def rules = GithubDesignatorRules.from(['api-url': 'https://api.github.test'])

        expect:
        rules.kinds().isEmpty()
        rules.extract(['base:main']).forKind('base') == new Designator.Absent()
    }

    // FR3: none() is the same nothing, for a fetcher built without rules at all
    def "none declares no kinds"() {
        expect:
        GithubDesignatorRules.none().kinds().isEmpty()
        GithubDesignatorRules.none().extract(['base:main']).forKind('base') == new Designator.Absent()
    }

    // FR3: a designators value that is not a map declares nothing -- the located load error
    //     GithubDesignatorsValidator raises is what the operator sees, not an adapter crash
    def "a non-map designators value declares no kinds"() {
        expect:
        GithubDesignatorRules.from(['api-url': 'x', designators: 'base:(.+)']).kinds().isEmpty()
    }

    // FR3: entries the loader has already refused are skipped rather than thrown on, so the
    //     aggregated load errors stay the operator's single report
    def "entries the loader refuses are skipped: #why"() {
        expect:
        rulesFor(rule).kinds() == expected as Set

        where:
        why | rule || expected
        'uncompilable regex' | [base: 'base:('] || []
        'no capture group' | [base: 'base:.+'] || []
        'two capture groups' | [base: '(base):(.+)'] || []
        'non-string rule' | [base: 42] || []
        'blank rule' | [base: '  '] || []
        'blank kind' | ['': 'base:(.+)'] || []
        'one good among the bad' | [base: 'base:(.+)', t: 'x('] || ['base']
    }

    // FR3: the compiled rules are inert value data -- a live adapter's extraction cannot change
    def "the rules copy the map they were built from"() {
        given:
        def designators = [base: 'base:(.+)']
        def rules = rulesFor(designators)

        when:
        designators.put('type', 'type:(.+)')

        then:
        rules.kinds() == ['base'] as Set
    }
}
