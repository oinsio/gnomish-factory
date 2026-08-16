package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * GithubTaskFetcher (FR2, FR5 of add-tracker-port): verifies {@code
 * fetchTask} builds the snapshot from the issue JSON, derives the logical
 * state from labels (Ready/Working/AwaitingHuman), reports {@code Gone} for a
 * closed or missing issue, and boundary-anchors claim holder and abort facts
 * to the latest boundary marker (claim/abort) rather than folding the whole
 * comment history unconditionally.
 *
 * Implements FR2, FR5 of add-tracker-port.
 */
class GithubTaskFetcherSpec extends Specification {

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
                // Matches everything rather than naming the adapter's package-private
                // GithubHttpUncheckedIOException (illegal cross-package access from this spec's
                // package, see FeedAutomatonOutageIntegrationSpec) -- harmless here since the only
                // exception this predicate ever actually sees is a real transport failure.
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private GithubTaskFetcher newFetcher(String workingLabel = 'gnomish:working',
            String needsHumanLabel = 'gnomish:needs-human', String deliveredLabel = 'gnomish:delivered') {
        new GithubTaskFetcher(newCache(), workingLabel, needsHumanLabel, deliveredLabel)
    }

    private GithubConditionalRequestCache newCache() {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        new GithubConditionalRequestCache(httpClient)
    }

    private TaskRef refFor(int issueNumber) {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', issueNumber).canonicalId())
    }

    def "reports Ready when neither the working nor needs-human label is present"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/5'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":5,"title":"Fix the widget","body":"details","state":"open",
                         "labels":[{"name":"gnomish:ready"}]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/5/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(5))

        then:
        result.state() == new TrackerTaskState.Ready()
        result.snapshot() == new TaskSnapshot(refFor(5).id(), 'Fix the widget', 'details')
    }

    def "reports Finished when the delivered label is present"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/9'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":9,"title":"Fix the widget","body":"details","state":"open",
                         "labels":[{"name":"gnomish:delivered"}]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/9/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(9))

        then:
        result.state() == new TrackerTaskState.Finished()
    }

    def "maps a null body to an empty string in the snapshot"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/6'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":6,"title":"No body here","body":null,"state":"open","labels":[]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/6/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(6))

        then:
        result.snapshot().body() == ''
    }

    def "reports Working with the holder from the latest active claim marker"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/7'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":7,"title":"t","body":"b","state":"open",
                         "labels":[{"name":"gnomish:working"}]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/7/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"}
                        ]
                        ''')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(7))

        then:
        result.state() == new TrackerTaskState.Working('gnomish-factory-a1')
    }

    def "boundary-anchors the claim holder to the claim marker posted after the latest abort"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/8'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":8,"title":"t","body":"b","state":"open",
                         "labels":[{"name":"gnomish:working"}]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/8/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T09:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1} -->\\n🤖 aborted"},
                          {"id":3,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-b2\\",\\"at\\":\\"2026-07-20T11:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"}
                        ]
                        ''')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(8))

        then: 'the holder is the claim after the latest abort, not the stale earlier one'
        result.state() == new TrackerTaskState.Working('gnomish-factory-b2')
        and: 'the abort marker before the current claim does not count toward abort facts'
        result.abortFacts().count() == 0
    }

    def "throws when the issue carries the working label but no active claim marker exists"() {
        given: 'an issue with the working label but no claim comment at all'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/17'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":17,"title":"t","body":"b","state":"open",
                         "labels":[{"name":"gnomish:working"}]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/17/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def fetcher = newFetcher()

        when:
        fetcher.fetchTask(refFor(17))

        then: 'the missing-claim-marker inconsistency surfaces as an infrastructure failure, not a wrong holder'
        thrown(GithubFeedQueryException)
    }

    def "reports AwaitingHuman with the reason from the latest park marker"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/9'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":9,"title":"t","body":"b","state":"open",
                         "labels":[{"name":"gnomish:needs-human"}]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/9/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"park\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1,\\"reason\\":\\"escalation\\"} -->\\n🤖 needs a decision"}
                        ]
                        ''')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(9))

        then:
        result.state() == new TrackerTaskState.AwaitingHuman(ParkReason.ESCALATION)
    }

    // FR1, NFR-P1 of enforce-finish-terminality: fetchTask derives finished from the same
    //     comments fetch already made for the state/abort facts — no extra API call
    def "reports finished = true on a Ready issue whose thread carries a FINISH marker"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/18'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":18,"title":"t","body":"b","state":"open",
                         "labels":[{"name":"gnomish:ready"}]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/18/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"finish\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T11:00:00Z\\",\\"version\\":1} -->\\n🤖 delivered"}
                        ]
                        ''')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(18))

        then:
        result.finished()
        wireMock.verify(1, getRequestedFor(urlEqualTo('/repos/acme/widgets/issues/18/comments?per_page=100')))
    }

    def "reports finished = false on an ordinary Ready issue with no FINISH marker"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/19'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":19,"title":"t","body":"b","state":"open",
                         "labels":[{"name":"gnomish:ready"}]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/19/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(19))

        then:
        !result.finished()
    }

    // enforce-finish-terminality design Q2 / risk: a CLOSED (Gone) issue reports finished = false and
    //     never reaches the decline path while closed — the Gone branch short-circuits before the
    //     comments are even fetched, so a lingering FINISH marker in the thread cannot flip the fact.
    //     The finished fact re-derives to true only once a human reopens the issue back to Ready.
    def "reports finished = false for a closed issue without fetching its comments"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/12'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":12,"title":"t","body":"b","state":"closed","state_reason":"completed",
                         "labels":[{"name":"gnomish:delivered"}]}
                        ''')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(12))

        then:
        result.state() instanceof TrackerTaskState.Gone
        !result.finished()
        wireMock.verify(0, getRequestedFor(urlEqualTo('/repos/acme/widgets/issues/12/comments?per_page=100')))
    }

    def "reports Gone for a closed issue, carrying state_reason as the closure reason (revocation context)"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/10'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":10,"title":"t","body":"b","state":"closed","state_reason":"completed","labels":[]}
                        ''')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(10))

        then:
        result.state() == new TrackerTaskState.Gone('completed')
    }

    def "reports Gone with no closure reason for a closed issue whose state_reason is absent"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/10'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":10,"title":"t","body":"b","state":"closed","labels":[]}
                        ''')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(10))

        then:
        result.state() == new TrackerTaskState.Gone()
    }

    def "reports Gone for a missing (404) issue without throwing"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/404'))
                .willReturn(aResponse().withStatus(404).withBody('{"message":"Not Found"}')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(404))

        then:
        result.state() == new TrackerTaskState.Gone()
        and: 'the gone snapshot is a real snapshot (id echoed as both id and title, empty body), never null'
        result.snapshot() == new TaskSnapshot(refFor(404).id(), refFor(404).id(), '')
    }

    def "abort facts fold only markers posted after the latest claim (boundary anchoring)"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/11'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        {"number":11,"title":"t","body":"b","state":"open",
                         "labels":[{"name":"gnomish:ready"}]}
                        ''')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/11/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T09:00:00Z\\",\\"version\\":1} -->\\n🤖 aborted (stale)"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                          {"id":3,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T11:00:00Z\\",\\"version\\":1} -->\\n🤖 aborted (current)"}
                        ]
                        ''')))
        def fetcher = newFetcher()

        when:
        def result = fetcher.fetchTask(refFor(11))

        then:
        result.abortFacts().count() == 1
        result.abortFacts().lastAbortAt() == Instant.parse('2026-07-20T11:00:00Z')
    }

    def "re-reading an unchanged task at a round boundary sends If-None-Match and treats 304 as no change"() {
        given: 'the issue and its comments each answer 200+ETag once, then 304 on the conditional re-read'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/12'))
                .inScenario('issue-boundary').whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"iss1"').withBody('''
                        {"number":12,"title":"t","body":"b","state":"open",
                         "labels":[{"name":"gnomish:working"}]}
                        '''))
                .willSetStateTo('issue-cached'))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/12'))
                .inScenario('issue-boundary').whenScenarioStateIs('issue-cached')
                .willReturn(aResponse().withStatus(304)))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/12/comments?per_page=100'))
                .inScenario('comments-boundary').whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(200).withHeader('ETag', '"com1"').withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"}
                        ]
                        '''))
                .willSetStateTo('comments-cached'))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/12/comments?per_page=100'))
                .inScenario('comments-boundary').whenScenarioStateIs('comments-cached')
                .willReturn(aResponse().withStatus(304)))
        def fetcher = newFetcher()

        when: 'the same fetcher (sharing one cache) reads the task twice, as a round-boundary check does'
        def first = fetcher.fetchTask(refFor(12))
        def second = fetcher.fetchTask(refFor(12))

        then: 'the 304 re-read reuses the cached issue and comments, still resolving the same live state'
        first.state() == new TrackerTaskState.Working('gnomish-factory-a1')
        second.state() == new TrackerTaskState.Working('gnomish-factory-a1')

        and: 'the second read carried If-None-Match with the cached ETag of each resource'
        wireMock.verify(getRequestedFor(urlEqualTo('/repos/acme/widgets/issues/12'))
                .withHeader('If-None-Match', WireMock.equalTo('"iss1"')))
        wireMock.verify(getRequestedFor(urlEqualTo('/repos/acme/widgets/issues/12/comments?per_page=100'))
                .withHeader('If-None-Match', WireMock.equalTo('"com1"')))
    }
}
