package com.github.oinsio.gnomish.adapter.tracker.github

import spock.lang.Specification

/**
 * Direct unit coverage of {@link GithubIssueDetailParser} (FR2, FR5 of add-tracker-port): the
 * {@code state_reason} field in particular, which {@code GithubTaskFetcherSpec}'s WireMock
 * fixtures never assert on directly (task 4.10's judgment call: not threaded into {@code
 * TrackerTaskState.Gone} yet, so nothing downstream reads it today) — covered here against the
 * parser's own package-private {@link GithubIssueDetail} result.
 *
 * <p>Implements FR2, FR5 of add-tracker-port.
 */
class GithubIssueDetailParserSpec extends Specification {

    def "parses a present state_reason on a closed issue"() {
        when:
        def detail = GithubIssueDetailParser.parse('''
                {"number":1,"title":"t","body":"b","state":"closed","state_reason":"completed","labels":[]}
                ''')

        then:
        detail.stateReason() == 'completed'
    }

    def "maps an absent state_reason to null"() {
        when:
        def detail = GithubIssueDetailParser.parse('''
                {"number":2,"title":"t","body":"b","state":"open","labels":[]}
                ''')

        then:
        detail.stateReason() == null
    }

    def "maps a JSON-null state_reason to null"() {
        when:
        def detail = GithubIssueDetailParser.parse('''
                {"number":3,"title":"t","body":"b","state":"open","state_reason":null,"labels":[]}
                ''')

        then:
        detail.stateReason() == null
    }
}
