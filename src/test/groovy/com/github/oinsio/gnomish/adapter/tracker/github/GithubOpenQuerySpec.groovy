package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * GithubOpenQuery (FR5, NFR-P1 of add-claim-heartbeat): verifies {@code
 * listOpen} queries the List Issues API once per open-state label (working and
 * needs-human, GitHub's {@code labels=} being AND-only), excludes pull
 * requests, resolves each Working task's claim comment to a holder and a
 * (comment id, updated_at) version, reports a Working issue whose live claim is
 * missing with an absent claim, and reuses the conditional-request cache so an
 * unchanged poll is a free 304.
 *
 * Implements FR5, NFR-P1 of add-claim-heartbeat.
 */
class GithubOpenQuerySpec extends Specification {

    private static final String WORKING_URL =
    '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aworking&sort=created&direction=asc&per_page=100'
    private static final String NEEDS_HUMAN_URL =
    '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aneeds-human&sort=created&direction=asc&per_page=100'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    private static RetryConfig fastRetryConfig() {
        RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ it instanceof GithubHttpUncheckedIOException })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private GithubOpenQuery newOpenQuery() {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        new GithubOpenQuery(cache, 'acme', 'widgets', 'gnomish:working', 'gnomish:needs-human')
    }

    private void stubWorkingFeed(String body) {
        wireMock.stubFor(get(urlEqualTo(WORKING_URL)).willReturn(aResponse().withStatus(200).withBody(body)))
    }

    private void stubNeedsHumanFeed(String body) {
        wireMock.stubFor(get(urlEqualTo(NEEDS_HUMAN_URL)).willReturn(aResponse().withStatus(200).withBody(body)))
    }

    private void stubComments(int issueNumber, String body) {
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody(body)))
    }

    private static String claimComment(long id, String instance, String at) {
        """[{"id":${id},"updated_at":"${at}","body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"${instance}\\",\\"at\\":\\"${at}\\",\\"version\\":1} -->\\n🤖 claimed"}]"""
    }

    def "FR5: queries List Issues once per open-state label, working and needs-human separately"() {
        given:
        stubWorkingFeed('[]')
        stubNeedsHumanFeed('[]')

        when:
        newOpenQuery().listOpen()

        then:
        wireMock.verify(getRequestedFor(urlEqualTo(WORKING_URL)))
        wireMock.verify(getRequestedFor(urlEqualTo(NEEDS_HUMAN_URL)))
    }

    def "FR5: reports a Working task with its holder and a (comment id, updated_at) claim version"() {
        given:
        stubWorkingFeed('[{"number":7}]')
        stubNeedsHumanFeed('[]')
        stubComments(7, claimComment(501L, 'gnomish-factory-a1', '2026-07-23T10:00:00Z'))

        when:
        def result = newOpenQuery().listOpen()

        then:
        result.size() == 1
        result[0].ref().id() == 'github:localhost/acme/widgets#7'
        result[0].state() == new TrackerTaskState.Working('gnomish-factory-a1')
        result[0].claimVersion() == new ClaimVersion('501', Instant.parse('2026-07-23T10:00:00Z'))
    }

    def "FR5: reports a needs-human task as AwaitingHuman with the park reason and an absent claim"() {
        given:
        stubWorkingFeed('[]')
        stubNeedsHumanFeed('[{"number":9}]')
        stubComments(9, '''
                [
                  {"id":1,"updated_at":"2026-07-23T10:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"park\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1,\\"reason\\":\\"escalation\\"} -->\\n🤖 needs a decision"}
                ]
                ''')

        when:
        def result = newOpenQuery().listOpen()

        then:
        result.size() == 1
        result[0].state() == new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION)
        result[0].claimVersion() == null
    }

    def "FR5: listing spans both open states, excluding a ready issue and an open PR labeled working"() {
        given: 'the working feed carries a real working issue and a PR; needs-human carries one issue'
        stubWorkingFeed('''
                [
                  {"number":7},
                  {"number":8,"pull_request":{"url":"https://api.github.com/repos/acme/widgets/pulls/8"}}
                ]
                ''')
        stubNeedsHumanFeed('[{"number":9}]')
        stubComments(7, claimComment(501L, 'gnomish-factory-a1', '2026-07-23T10:00:00Z'))
        stubComments(9, '''
                [
                  {"id":1,"updated_at":"2026-07-23T10:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"park\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1,\\"reason\\":\\"escalation\\"} -->\\n🤖 needs a decision"}
                ]
                ''')

        when:
        def result = newOpenQuery().listOpen()

        then: 'exactly the two open tasks come back — never the PR'
        result*.ref()*.id() as Set == [
            'github:localhost/acme/widgets#7',
            'github:localhost/acme/widgets#9'
        ] as Set
        def working = result.find { it.ref().id() == 'github:localhost/acme/widgets#7' }
        working.state() == new TrackerTaskState.Working('gnomish-factory-a1')
        working.claimVersion() == new ClaimVersion('501', Instant.parse('2026-07-23T10:00:00Z'))
    }

    def "FR5: a Working issue whose live claim comment is missing is reported with an absent claim and the last-known holder"() {
        given: 'a claim voided by a later abort boundary, so no live claim resolves'
        stubWorkingFeed('[{"number":7}]')
        stubNeedsHumanFeed('[]')
        stubComments(7, '''
                [
                  {"id":1,"updated_at":"2026-07-23T09:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-23T09:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                  {"id":2,"updated_at":"2026-07-23T10:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 aborted"}
                ]
                ''')

        when:
        def result = newOpenQuery().listOpen()

        then: 'the task is still listed (working label stands) with a null version and the recovered holder'
        result.size() == 1
        result[0].state() == new TrackerTaskState.Working('gnomish-factory-a1')
        result[0].claimVersion() == null
    }

    def "FR5: with the live claim voided, the recovered holder is the claim marker's instance, not a later boundary marker's"() {
        given: 'a claim by instance a1, then an abort boundary posted by a DIFFERENT instance b2 — no live claim resolves'
        stubWorkingFeed('[{"number":7}]')
        stubNeedsHumanFeed('[]')
        stubComments(7, '''
                [
                  {"id":1,"updated_at":"2026-07-23T09:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-23T09:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                  {"id":2,"updated_at":"2026-07-23T10:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-b2\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 aborted"}
                ]
                ''')

        when:
        def result = newOpenQuery().listOpen()

        then: 'the recovered holder is the CLAIM marker instance a1 — never the later abort boundary instance b2'
        result.size() == 1
        result[0].state() == new TrackerTaskState.Working('gnomish-factory-a1')
        result[0].claimVersion() == null
    }

    def "FR5: a working-labeled issue with no claim footprint at all is not listed (no holder to name)"() {
        given:
        stubWorkingFeed('[{"number":7}]')
        stubNeedsHumanFeed('[]')
        stubComments(7, '[]')

        when:
        def result = newOpenQuery().listOpen()

        then:
        result.isEmpty()
    }

    def "NFR-P1: an unchanged poll re-sends If-None-Match and handles 304 as no change"() {
        given: 'the working feed answers 200+ETag once, then 304 on the conditional re-read'
        wireMock.stubFor(get(urlEqualTo(WORKING_URL))
                .inScenario('feed').whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"w1"').withBody('[]'))
                .willSetStateTo('cached'))
        wireMock.stubFor(get(urlEqualTo(WORKING_URL))
                .inScenario('feed').whenScenarioStateIs('cached')
                .willReturn(aResponse().withStatus(304)))
        stubNeedsHumanFeed('[]')
        def openQuery = newOpenQuery()
        openQuery.listOpen()

        when: 'the same open query (sharing one cache) polls again'
        def second = openQuery.listOpen()

        then: 'the 304 re-read reuses the cached (empty) feed body'
        second.isEmpty()

        and: 'the second working-label request carried If-None-Match with the cached ETag'
        wireMock.verify(getRequestedFor(urlEqualTo(WORKING_URL))
                .withHeader('If-None-Match', equalTo('"w1"')))
    }

    def "reports an infrastructure failure when a working issue's comments cannot be read"() {
        given:
        stubWorkingFeed('[{"number":7}]')
        stubNeedsHumanFeed('[]')
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/7/comments?per_page=100'))
                .willReturn(aResponse().withStatus(403).withBody('{"message":"Forbidden"}')))

        when:
        newOpenQuery().listOpen()

        then:
        thrown(GithubFeedQueryException)
    }
}
