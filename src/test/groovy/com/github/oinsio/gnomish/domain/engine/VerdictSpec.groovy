package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * Verdict: the sealed triad for the result of a single verify check —
 * {@code Pass}, {@code Fail(findings)} (a quality failure), and
 * {@code CannotVerify(reason, details)} (an infrastructure failure where no
 * verdict could be obtained). Implements FR4 of add-stage-engine.
 */
class VerdictSpec extends Specification {

    // FR4: Pass carries nothing — any two Pass instances are the same value
    def "Pass instances are value-equal"() {
        expect: 'two independently constructed passes are equal'
        new Verdict.Pass() == new Verdict.Pass()
    }

    // FR4: Fail exposes its findings exactly as constructed
    def "Fail exposes findings as constructed"() {
        given: 'a list of findings'
        def findings = [
            new Finding('first', null, null),
            new Finding('second', 'Foo.java:1', null),
        ]

        when: 'a Fail is created'
        def fail = new Verdict.Fail(findings)

        then: 'the findings are exposed exactly as constructed'
        fail.findings() == findings
    }

    // FR4: a check may fail with no structured findings — an empty list is valid
    def "Fail accepts an empty findings list"() {
        when: 'a Fail is created with no findings'
        def fail = new Verdict.Fail([])

        then: 'the findings list is empty'
        fail.findings().isEmpty()
    }

    // FR4: findings are copied on construction — later source mutation cannot leak in
    def "the Fail findings list is defensively copied from the source"() {
        given: 'a mutable source list'
        def source = [
            new Finding('first', null, null)
        ]

        when: 'a Fail is created and the source is then mutated'
        def fail = new Verdict.Fail(source)
        source.add(new Finding('sneaked in', null, null))

        then: 'the Fail keeps its original single finding'
        fail.findings().size() == 1
        fail.findings()[0].message() == 'first'
    }

    // FR4: the exposed findings list is unmodifiable — no one edits it in place
    def "the exposed Fail findings list is unmodifiable"() {
        given: 'a Fail'
        def fail = new Verdict.Fail([new Finding('a', null, null)])

        when: 'a caller tries to add a finding'
        fail.findings().add(new Finding('b', null, null))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR4: CannotVerify exposes its reason and details as constructed
    def "CannotVerify exposes reason and details as constructed"() {
        when: 'a CannotVerify is created'
        def cv = new Verdict.CannotVerify('binary not found', 'PATH=/usr/bin')

        then: 'both components are exposed exactly as constructed'
        cv.reason() == 'binary not found'
        cv.details() == 'PATH=/usr/bin'
    }

    // FR4/NFR-O1: details holds the preserved stack trace, but may be empty when there is none
    def "CannotVerify accepts empty details"() {
        when: 'a CannotVerify is created with no underlying detail'
        def cv = new Verdict.CannotVerify('check id unknown', '')

        then: 'the empty details are exposed as constructed'
        cv.details() == ''
    }

    // FR4: reason is the human-facing short cause — a blank reason is meaningless and rejected
    def "CannotVerify rejects a blank reason with the component name in the message"() {
        when: 'a CannotVerify is created with a blank reason'
        new Verdict.CannotVerify(reason, 'details')

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('CannotVerify.reason')

        where:
        reason << ['', '   ', '\t', ' \n']
    }

    // FR4: the triad is exhaustive — a switch over Verdict handles all three variants
    def "a switch over Verdict handles Pass, Fail and CannotVerify"() {
        expect: 'each variant is matched by its own arm'
        label(verdict) == expected

        where:
        verdict                                          || expected
        new Verdict.Pass()                               || 'pass'
        new Verdict.Fail([])                             || 'fail'
        new Verdict.CannotVerify('r', '')                || 'cannot-verify'
    }

    private static String label(Verdict verdict) {
        switch (verdict) {
                    case Verdict.Pass -> 'pass'
                    case Verdict.Fail -> 'fail'
                    case Verdict.CannotVerify -> 'cannot-verify'
                }
    }
}
