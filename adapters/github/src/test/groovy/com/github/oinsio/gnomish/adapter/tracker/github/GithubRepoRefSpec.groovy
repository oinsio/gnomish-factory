package com.github.oinsio.gnomish.adapter.tracker.github

import spock.lang.Specification

/**
 * {@link GithubRepoRef} (FR9, FR17 of add-tracker-port): splits {@code tracker.github.repo}'s
 * {@code "owner/repo"} value, shared by {@link GithubRefExpander} and {@link
 * GithubTrackerAdapterFactory} so the split logic exists exactly once.
 */
class GithubRepoRefSpec extends Specification {

    def "parses a well-formed owner/repo string"() {
        expect:
        GithubRepoRef.parse('acme/widgets') == new GithubRepoRef('acme', 'widgets')
    }

    def "rejects #description"() {
        when:
        GithubRepoRef.parse(malformed)

        then:
        thrown(IllegalArgumentException)

        where:
        malformed | description
        'acme' | 'no slash'
        '/widgets' | 'empty owner'
        'acme/' | 'empty repo'
        '' | 'empty string'
    }
}
