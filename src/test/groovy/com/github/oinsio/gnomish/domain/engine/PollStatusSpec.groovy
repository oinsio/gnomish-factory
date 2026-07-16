package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * PollStatus: the sealed quartet for one poll of an external verify check —
 * {@code Pass}, {@code Fail(findings)} (a quality failure), {@code Running} (no
 * verdict yet — keep polling), and {@code CannotVerify(reason, details)} (an
 * infrastructure failure where no result could be obtained). Mirrors
 * {@code Verdict} plus the {@code Running} variant. Implements FR3, D2 of
 * add-stage-engine.
 */
class PollStatusSpec extends Specification {

    // FR3: Pass carries nothing — any two Pass instances are the same value
    def "Pass instances are value-equal"() {
        expect: 'two independently constructed passes are equal'
        new PollStatus.Pass() == new PollStatus.Pass()
    }

    // FR3: Running carries nothing — any two Running instances are the same value
    def "Running instances are value-equal"() {
        expect: 'two independently constructed running statuses are equal'
        new PollStatus.Running() == new PollStatus.Running()
    }

    // FR3: Fail exposes its findings exactly as constructed
    def "Fail exposes findings as constructed"() {
        given: 'a list of findings'
        def findings = [
            new Finding('first', null, null),
            new Finding('second', 'Foo.java:1', null),
        ]

        when: 'a Fail is created'
        def fail = new PollStatus.Fail(findings)

        then: 'the findings are exposed exactly as constructed'
        fail.findings() == findings
    }

    // FR3: a poll may fail with no structured findings — an empty list is valid
    def "Fail accepts an empty findings list"() {
        when: 'a Fail is created with no findings'
        def fail = new PollStatus.Fail([])

        then: 'the findings list is empty'
        fail.findings().isEmpty()
    }

    // FR3: findings are copied on construction — later source mutation cannot leak in
    def "the Fail findings list is defensively copied from the source"() {
        given: 'a mutable source list'
        def source = [
            new Finding('first', null, null)
        ]

        when: 'a Fail is created and the source is then mutated'
        def fail = new PollStatus.Fail(source)
        source.add(new Finding('sneaked in', null, null))

        then: 'the Fail keeps its original single finding'
        fail.findings().size() == 1
        fail.findings()[0].message() == 'first'
    }

    // FR3: the exposed findings list is unmodifiable — no one edits it in place
    def "the exposed Fail findings list is unmodifiable"() {
        given: 'a Fail'
        def fail = new PollStatus.Fail([new Finding('a', null, null)])

        when: 'a caller tries to add a finding'
        fail.findings().add(new Finding('b', null, null))

        then: 'the modification is rejected'
        thrown(UnsupportedOperationException)
    }

    // FR3: CannotVerify exposes its reason and details as constructed
    def "CannotVerify exposes reason and details as constructed"() {
        when: 'a CannotVerify is created'
        def cv = new PollStatus.CannotVerify('service unavailable', 'HTTP 503')

        then: 'both components are exposed exactly as constructed'
        cv.reason() == 'service unavailable'
        cv.details() == 'HTTP 503'
    }

    // FR3: details holds the preserved detail, but may be empty when there is none
    def "CannotVerify accepts empty details"() {
        when: 'a CannotVerify is created with no underlying detail'
        def cv = new PollStatus.CannotVerify('check id unknown', '')

        then: 'the empty details are exposed as constructed'
        cv.details() == ''
    }

    // FR3: reason is the human-facing short cause — a blank reason is meaningless and rejected
    def "CannotVerify rejects a blank reason with the component name in the message"() {
        when: 'a CannotVerify is created with a blank reason'
        new PollStatus.CannotVerify(reason, 'details')

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('CannotVerify.reason')

        where:
        reason << ['', '   ', '\t', ' \n']
    }

    // FR3: the quartet is exhaustive — a switch over PollStatus handles all four variants
    def "a switch over PollStatus handles Pass, Fail, Running and CannotVerify"() {
        expect: 'each variant is matched by its own arm'
        label(status) == expected

        where:
        status                                  || expected
        new PollStatus.Pass()                   || 'pass'
        new PollStatus.Fail([])                 || 'fail'
        new PollStatus.Running()                || 'running'
        new PollStatus.CannotVerify('r', '')    || 'cannot-verify'
    }

    private static String label(PollStatus status) {
        switch (status) {
                    case PollStatus.Pass -> 'pass'
                    case PollStatus.Fail -> 'fail'
                    case PollStatus.Running -> 'running'
                    case PollStatus.CannotVerify -> 'cannot-verify'
                }
    }
}
