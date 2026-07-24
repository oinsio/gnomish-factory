package com.github.oinsio.gnomish.adapter.tracker.github

import spock.lang.Specification

/**
 * GithubRefExpander (FR9 of add-tracker-port, design D4, D7): expands a recognized GitHub short
 * ref's issue number into the canonical {@code TaskRef} for the project's configured
 * tracker.github binding, splitting the validated {@code repo} ("owner/repo") key and delegating
 * to GithubTaskId for the actual canonical-id construction.
 *
 * Implements FR9 of add-tracker-port.
 */
class GithubRefExpanderSpec extends Specification {

    def "expands using the default api-url to a host-less canonical id"() {
        given:
        def subsection = ['api-url': 'https://api.github.com', 'repo': 'acme/widgets']

        when:
        def ref = GithubRefExpander.expand(subsection, 42)

        then:
        ref.id() == 'github:acme/widgets#42'
    }

    def "expands using a non-default api-url to a host-included canonical id"() {
        given:
        def subsection = ['api-url': 'https://ghe.example.com/api/v3', 'repo': 'acme/widgets']

        when:
        def ref = GithubRefExpander.expand(subsection, 7)

        then:
        ref.id() == 'github:ghe.example.com/acme/widgets#7'
    }

    def "rejects a missing repo key"() {
        given:
        def subsection = ['api-url': 'https://api.github.com']

        when:
        GithubRefExpander.expand(subsection, 42)

        then:
        thrown(IllegalArgumentException)
    }

    def "rejects a malformed repo value: #repo"() {
        given:
        def subsection = ['api-url': 'https://api.github.com', 'repo': repo]

        when:
        GithubRefExpander.expand(subsection, 42)

        then:
        thrown(IllegalArgumentException)

        where:
        repo << [
            'widgets',
            '/widgets',
            'acme/',
            ''
        ]
    }

    def "rejects a missing api-url key"() {
        given:
        def subsection = ['repo': 'acme/widgets']

        when:
        GithubRefExpander.expand(subsection, 42)

        then:
        thrown(IllegalArgumentException)
    }
}
