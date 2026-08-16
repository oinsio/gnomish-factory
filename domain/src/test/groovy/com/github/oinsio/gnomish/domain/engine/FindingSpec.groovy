package com.github.oinsio.gnomish.domain.engine

import spock.lang.Specification

/**
 * Finding: one structured problem carried by a {@link Verdict.Fail}. Contract:
 * {@code message} is a non-blank description of what is wrong; {@code location}
 * and {@code details} are optional locators, {@code null} when absent. Inert
 * value data compared by content. Implements FR4 of add-stage-engine.
 */
class FindingSpec extends Specification {

    // FR4: a finding exposes its message, location and details as constructed
    def "a finding exposes message, location and details as constructed"() {
        when: 'a finding is created with all components'
        def finding = new Finding('null returned', 'Foo.java:12', 'expected non-null')

        then: 'each component is exposed exactly as constructed'
        finding.message() == 'null returned'
        finding.location() == 'Foo.java:12'
        finding.details() == 'expected non-null'
    }

    // FR4: a validated message round-trips exactly — it is not silently emptied
    // (pins requireNonBlank's return against an empty-string return mutation)
    def "a validated message round-trips the constructed text"() {
        expect: 'the accessor returns the exact non-blank message it was built with'
        new Finding('file not found', null, null).message() == 'file not found'
    }

    // FR4: location and details are optional — null is accepted for both
    def "null location and details are accepted"() {
        when: 'a finding is created with only a message'
        def finding = new Finding('something is wrong', null, null)

        then: 'the optional locators are null'
        finding.location() == null
        finding.details() == null
    }

    // FR4: message is what is wrong — a blank message is meaningless and rejected
    def "blank message is rejected with the component name in the message"() {
        when: 'a finding is created with a blank message'
        new Finding(message, null, null)

        then: 'construction fails and the message names the blank component'
        def failure = thrown(IllegalArgumentException)
        failure.message.contains('Finding.message')

        where:
        message << ['', '   ', '\t', ' \n']
    }

    // FR4: findings are values — equal content means equal findings
    def "findings with the same components are equal values"() {
        expect: 'two independently constructed findings with equal components are equal'
        new Finding('m', 'loc', 'det') == new Finding('m', 'loc', 'det')

        and: 'a difference in any component breaks equality'
        new Finding('m', 'loc', 'det') != new Finding('m', 'loc', 'other')
    }
}
