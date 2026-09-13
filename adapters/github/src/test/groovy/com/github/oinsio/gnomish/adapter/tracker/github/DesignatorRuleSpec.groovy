package com.github.oinsio.gnomish.adapter.tracker.github

import spock.lang.Specification

/**
 * DesignatorRule (github-tracker spec, "Designator rule without exactly one
 * capture group is a load error"): the single definition of what a
 * {@code tracker.github.designators} rule is, shared by the load-time
 * validator and the extraction that runs on the rules it passed.
 *
 * Implements FR3 of add-base-ref-resolution.
 */
class DesignatorRuleSpec extends Specification {

    // FR3: a rule with exactly one capture group is usable, and its pattern is the one that extracts
    def "a rule with exactly one capture group grades clean"() {
        when:
        def graded = DesignatorRule.grade('base:(.+)')

        then:
        graded.problem().isEmpty()
        graded.pattern().isPresent()
        graded.pattern().get().matcher('base:release/1.18').matches()
    }

    // FR3: the two grading outcomes are exclusive by construction -- this is what keeps the
    //     validator and the extractor from accepting different sets of rules
    def "a rule has a pattern if and only if it has no problem: #why"() {
        when:
        def graded = DesignatorRule.grade(rule)

        then:
        graded.pattern().isPresent() == usable
        graded.problem().isEmpty() == usable

        where:
        why | rule || usable
        'one group' | 'base:(.+)' || true
        'one group, others non-capturing' | '(?:gnomish:)?type:(.+)' || true
        'no group' | 'base:.+' || false
        'two groups' | '(base):(.+)' || false
        'does not compile' | 'base:(' || false
        'blank' | '   ' || false
    }

    // FR3: the problem is the operator-facing message -- it names the rule and what to do
    def "the problem message names the rule and the remedy: #why"() {
        expect:
        DesignatorRule.grade(rule).problem().get().contains(expected)

        where:
        why | rule || expected
        'no group' | 'base:.+' || 'exactly one capture group'
        'two groups' | '(base):(.+)' || "make the extra groups non-capturing with '(?:...)'"
        'does not compile' | 'base:(' || 'not a valid regular expression'
    }

    // FR3: whatever the validator refuses, the extractor skips -- one grading, two readers
    def "the validator's verdict and the extractor's verdict agree: #rule"() {
        given:
        def errors = GithubDesignatorsValidator.validate('config.yaml', 'tracker.github.designators', [base: rule])
        def kinds = GithubDesignatorRules.from(['api-url': 'x', designators: [base: rule]]).kinds()

        expect:
        errors.isEmpty() == kinds.contains('base')

        where:
        rule << [
            'base:(.+)',
            '(?:gnomish:)?type:(.+)',
            'base:.+',
            '(base):(.+)',
            'base:(',
            '   ',
            '()',
            '(?<value>.+)'
        ]
    }
}
