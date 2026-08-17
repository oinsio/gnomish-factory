package com.github.oinsio.gnomish.adapter.check.http

import spock.lang.Specification

/**
 * FR4, FR9 of add-plugin-architecture: the http provider's operator subsection selects the provider
 * and nothing else — it has no connection to configure, since every check carries its own target —
 * so an empty subsection is the complete form and any key in it is a located error rather than a
 * silently unread expectation.
 */
class HttpCheckSubsectionValidatorSpec extends Specification {

    private static final String FILE = 'application.yaml'
    private static final String WHERE = 'factory.check.http'

    // NFR-S2, D5: the subsection's one setting is the egress allowlist — operator-owned, never the
    //     manifest's, which is what makes "a manifest cannot widen it" true by construction.
    def "an allowlist of hosts is the subsection's one accepted key"() {
        expect:
        new HttpCheckSubsectionValidator()
                .validate(FILE, WHERE, [allowlist: [
                        'sonar.example.com',
                        '*.ci.example.com',
                        '10.1.2.3'
                    ]])
                .isEmpty()
    }

    def "an allowlist that is not a list is a located error"() {
        when:
        def errors = new HttpCheckSubsectionValidator().validate(FILE, WHERE, [allowlist: 'sonar.example.com'])

        then:
        errors.size() == 1
        errors[0].where() == 'factory.check.http.allowlist'
        errors[0].message().contains('list of permitted hosts')
    }

    def "an entry that is not a bare host is a located error naming it"() {
        when:
        def errors = new HttpCheckSubsectionValidator().validate(FILE, WHERE, [allowlist: [entry]])

        then:
        errors.size() == 1
        errors[0].where() == 'factory.check.http.allowlist'
        errors[0].message().contains('is not a host')

        where:
        entry << [
            'https://sonar.example.com',
            'sonar.example.com/api',
            'sonar.example.com:443',
            '  ',
            'a b.com'
        ]
    }

    def "a bracketed IPv6 literal is a legal entry"() {
        expect:
        new HttpCheckSubsectionValidator().validate(FILE, WHERE, [allowlist: ['[fd00::1]']]).isEmpty()
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
        errors[0].message().contains('allowlist')
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
