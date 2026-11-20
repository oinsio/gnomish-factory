package com.github.oinsio.gnomish.adapter.check.http

import spock.lang.Specification

/**
 * FR4, FR9 of add-plugin-architecture; FR17 of migrate-verify-to-executor-contract: the http
 * provider's operator subsection selects the provider and nothing else — it has no connection to configure, since every check carries its own target —
 * so an empty subsection is the complete form and any key in it is a located error rather than a
 * silently unread expectation.
 */
class HttpCheckSubsectionValidatorSpec extends Specification {

    private static final String FILE = 'application.yaml'
    private static final String WHERE = 'factory.check.http'

    // FR17 of migrate-verify-to-executor-contract: the list that lived here moved to the operator
    //     egress block; the key is no longer a setting of this subsection.
    def "the former allowlist key is a located error pointing at the in-process scope"() {
        when:
        def errors = new HttpCheckSubsectionValidator().validate(FILE, WHERE, [allowlist: ['sonar.example.com']])

        then:
        errors.size() == 1
        errors[0].where() == 'factory.check.http.allowlist'
        errors[0].message().contains('factory.egress.in-process')
    }

    def "an empty subsection is the correct and complete form"() {
        expect:
        new HttpCheckSubsectionValidator().validate(FILE, WHERE, [:]).isEmpty()
    }

    def "any key is a located error naming it"() {
        when:
        def errors = new HttpCheckSubsectionValidator().validate(FILE, WHERE, [('api-url'): 'https://x.invalid'])

        then:
        errors.size() == 1
        errors[0].file() == FILE
        errors[0].where() == 'factory.check.http.api-url'
        errors[0].message().contains("unknown key 'api-url'")
        errors[0].message().contains('factory.egress.in-process')
    }

    def "every offending key is reported, in key order"() {
        expect:
        new HttpCheckSubsectionValidator()
                .validate(FILE, WHERE, [url: 'x', ('api-url'): 'y'])
                .collect {
                    it.where()
                } == [
                    'factory.check.http.api-url',
                    'factory.check.http.url'
                ]
    }
}
