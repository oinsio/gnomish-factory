package com.github.oinsio.gnomish.adapter.tracker.github

import spock.lang.Specification

/**
 * GithubTaskId (FR16 of add-tracker-port, design D7, D15): builds and parses
 * the canonical task id {@code github:owner/repo#42}, including the host only
 * when the configured {@code api-url} is non-default after normalization
 * (trim whitespace, lowercase scheme/host, drop one trailing slash).
 *
 * Implements FR16 of add-tracker-port.
 */
class GithubTaskIdSpec extends Specification {

    def "default host is omitted: trailing-slash api-url still resolves to default"() {
        when:
        def id = GithubTaskId.build('https://api.github.com/', 'acme', 'widgets', 42)

        then:
        id.canonicalId() == 'github:acme/widgets#42'
    }

    def "bare default api-url with no trailing slash resolves to default"() {
        when:
        def id = GithubTaskId.build('https://api.github.com', 'acme', 'widgets', 42)

        then:
        id.canonicalId() == 'github:acme/widgets#42'
    }

    def "api-url normalization tolerates whitespace and scheme/host case"() {
        when:
        def id = GithubTaskId.build('  HTTPS://API.GITHUB.COM/  ', 'acme', 'widgets', 42)

        then:
        id.canonicalId() == 'github:acme/widgets#42'
    }

    def "a different scheme on the default host is non-default"() {
        when: 'the scheme is http instead of the default https, host otherwise identical'
        def id = GithubTaskId.build('http://api.github.com', 'acme', 'widgets', 42)

        then: 'the host is included — a scheme mismatch alone makes the api-url non-default'
        id.canonicalId() == 'github:api.github.com/acme/widgets#42'
    }

    def "enterprise host is included: a different host is non-default"() {
        when:
        def id = GithubTaskId.build('https://ghe.example.com/api/v3', 'acme', 'widgets', 42)

        then:
        id.canonicalId() == 'github:ghe.example.com/acme/widgets#42'
    }

    def "a non-default port on the default host is non-default but only the host is included"() {
        when:
        def id = GithubTaskId.build('https://api.github.com:8443', 'acme', 'widgets', 42)

        then:
        id.canonicalId() == 'github:api.github.com/acme/widgets#42'
    }

    def "a non-default path on the default host is non-default but only the host is included"() {
        when:
        def id = GithubTaskId.build('https://api.github.com/enterprise', 'acme', 'widgets', 42)

        then:
        id.canonicalId() == 'github:api.github.com/acme/widgets#42'
    }

    def "parse round-trips a default-host canonical id back to owner, repo, issue number"() {
        when:
        def parsed = GithubTaskId.parse('github:acme/widgets#42')

        then:
        parsed.host() == ''
        parsed.owner() == 'acme'
        parsed.repo() == 'widgets'
        parsed.issueNumber() == 42
    }

    def "parse round-trips an enterprise-host canonical id back to host, owner, repo, issue number"() {
        when:
        def parsed = GithubTaskId.parse('github:ghe.example.com/acme/widgets#42')

        then:
        parsed.host() == 'ghe.example.com'
        parsed.owner() == 'acme'
        parsed.repo() == 'widgets'
        parsed.issueNumber() == 42
    }

    def "build then parse round-trips to the same components: #apiUrl"() {
        given:
        def built = GithubTaskId.build(apiUrl, 'acme', 'widgets', 42)

        when:
        def reparsed = GithubTaskId.parse(built.canonicalId())

        then:
        reparsed == built

        where:
        apiUrl << [
            'https://api.github.com',
            'https://api.github.com/',
            'https://ghe.example.com/api/v3'
        ]
    }

    def "parse then rebuild yields the same canonical string"() {
        given:
        def original = 'github:ghe.example.com/acme/widgets#7'

        when:
        def parsed = GithubTaskId.parse(original)
        def rebuilt = new GithubTaskId(parsed.host(), parsed.owner(), parsed.repo(), parsed.issueNumber())

        then:
        rebuilt.canonicalId() == original
    }

    def "a canonical id rejects a malformed string: #bad"() {
        when:
        GithubTaskId.parse(bad)

        then:
        thrown(IllegalArgumentException)

        where:
        bad << [
            'acme/widgets#42',
            'github:acme/widgets',
            'github:acme#42',
            'github:#42',
            'github:acme/widgets#abc',
            ''
        ]
    }

    def "the canonical prefix is a fixed code constant, not configuration"() {
        expect:
        GithubTaskId.build('https://api.github.com', 'acme', 'widgets', 1).canonicalId().startsWith('github:')
    }
}
