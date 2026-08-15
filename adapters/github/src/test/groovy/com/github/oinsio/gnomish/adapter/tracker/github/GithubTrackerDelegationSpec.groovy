package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * {@link GithubTracker}'s read/return and remaining write delegations (task 4.16), the counterpart
 * to {@code GithubTrackerSpec}: proves {@code listReady}/{@code listOpen}/{@code collectDecisions}
 * return exactly the collaborator's value (not an engine-substituted empty list or {@code null}),
 * that {@code claim} returns the collaborator's non-null result, and that {@code
 * declineFinished}/{@code acknowledgeDecision}/{@code recordProgress} actually invoke their
 * collaborator (the delegating call is not silently dropped). Split from {@code GithubTrackerSpec}
 * to keep each spec within the file-size cap; both use real collaborators over WireMock (the
 * collaborator classes are {@code final}, so mocking them is not an option).
 *
 * <p>Implements FR1, FR4 of add-tracker-port; FR1 of fix-abort-progress-reset.
 */
class GithubTrackerDelegationSpec extends Specification {

    private static final int ISSUE_NUMBER = 50
    private static final String READY_FEED_URL =
    '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aready&sort=created&direction=asc&per_page=100'
    private static final String WORKING_FEED_URL =
    '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aworking&sort=created&direction=asc&per_page=100'
    private static final String NEEDS_HUMAN_FEED_URL =
    '/repos/acme/widgets/issues?state=open&labels=gnomish%3Aneeds-human&sort=created&direction=asc&per_page=100'
    private static final String COMMENTS_URL =
    "/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments?per_page=100"
    private static final String POST_COMMENTS_URL =
    "/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"

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
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private GithubTracker newTracker() {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def labelOps = new GithubLabelOps(httpClient)
        def cache = new GithubConditionalRequestCache(httpClient)
        new GithubTracker(
                new GithubFeedQuery(cache, 'acme', 'widgets', 'gnomish:ready'),
                new GithubTaskFetcher(cache, 'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered'),
                new GithubClaimLease(httpClient, labelOps, 'gnomish:ready', 'gnomish:working'),
                new GithubStateWrites(httpClient, labelOps, 'gnomish-factory-x7k2q1',
                'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered', 'gnomish:ready'),
                new GithubCorrespondence(httpClient, 'gnomish-factory-x7k2q1'),
                new GithubDecisions(httpClient, 'gnomish-factory-x7k2q1'),
                new GithubHeartbeat(httpClient, 'gnomish-factory-x7k2q1'),
                new GithubOpenQuery(cache, 'acme', 'widgets', 'gnomish:working', 'gnomish:needs-human'),
                new GithubStaleClaimRemoval(httpClient, labelOps, 'gnomish-factory-x7k2q1',
                'gnomish:working', 'gnomish:ready'))
    }

    private TaskRef ref() {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', ISSUE_NUMBER).canonicalId())
    }

    private void stubGet(String url, String body) {
        wireMock.stubFor(get(urlEqualTo(url)).willReturn(aResponse().withStatus(200).withBody(body)))
    }

    private void stubComment() {
        wireMock.stubFor(post(urlEqualTo(POST_COMMENTS_URL))
                .willReturn(aResponse().withStatus(201).withBody('{"id":1,"body":"whatever"}')))
    }

    private void stubLabelTransition(String removedLabelEncoded) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/labels"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/labels/${removedLabelEncoded}"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
    }

    def "listReady returns the feed collaborator's non-empty result, not a substituted empty list"() {
        given: 'the ready feed carries one issue, its comment thread empty'
        stubGet(READY_FEED_URL, '[{"number":5,"title":"Fix the widget"}]')
        stubGet('/repos/acme/widgets/issues/5/comments?per_page=100', '[]')

        when:
        def result = newTracker().listReady(10)

        then: 'the tracker forwards the collaborator list unchanged (an emptyList substitute would fail this)'
        result.size() == 1
        result[0].title() == 'Fix the widget'
    }

    def "listOpen returns the open-query collaborator's non-empty result, not a substituted empty list"() {
        given: 'one working issue with a live claim, no needs-human issues'
        stubGet(WORKING_FEED_URL, '[{"number":7}]')
        stubGet(NEEDS_HUMAN_FEED_URL, '[]')
        stubGet('/repos/acme/widgets/issues/7/comments?per_page=100',
                '''[{"id":501,"updated_at":"2026-07-23T10:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a1\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"}]''')

        when:
        def result = newTracker().listOpen()

        then:
        result.size() == 1
        result[0].state() == new TrackerTaskState.Working('gnomish-factory-a1')
    }

    def "collectDecisions returns the decisions collaborator's non-empty replies, not a substituted empty list"() {
        given: 'the thread carries one plain operator reply (no structural marker)'
        stubGet(COMMENTS_URL,
                '[{"id":1,"body":"please retry the build","created_at":"2026-07-20T10:00:00Z"}]')

        when:
        def result = newTracker().collectDecisions(ref())

        then:
        result.size() == 1
        result[0].body() == 'please retry the build'
    }

    def "claim returns the claim collaborator's non-null result"() {
        given: 'an uncontested claim: label transition succeeds, own claim comment is the only one'
        stubLabelTransition('gnomish%3Aready')
        wireMock.stubFor(post(urlEqualTo(POST_COMMENTS_URL))
                .willReturn(aResponse().withStatus(201).withBody('{"id":100}')))
        stubGet(COMMENTS_URL, '[]')

        when:
        def result = newTracker().claim(ref(), 'gnomish-factory-x7k2q1')

        then: 'a non-null Acquired result is forwarded (a null substitute would fail the instanceof)'
        result instanceof ClaimResult.Acquired
    }

    def "declineFinished delegates to GithubStateWrites, posting a note marker after the terminal transition"() {
        given: 'the issue is open (not yet terminal), so decline transitions ready to delivered and posts a note'
        stubGet("/repos/acme/widgets/issues/${ISSUE_NUMBER}",
                '{"number":50,"title":"t","body":"b","state":"open","labels":[{"name":"gnomish:ready"}]}')
        stubLabelTransition('gnomish%3Aready')
        stubComment()

        when:
        newTracker().declineFinished(ref(), 'not actually done')

        then: 'the delegated call fired — a dropped void call would post no comment at all'
        wireMock.verify(postRequestedFor(urlEqualTo(POST_COMMENTS_URL))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"note"'))))
    }

    def "acknowledgeDecision delegates to GithubDecisions, posting an ack marker"() {
        given:
        stubComment()

        when:
        newTracker().acknowledgeDecision(ref(), 'proceed with plan A')

        then: 'the delegated call fired — a dropped void call would post no comment at all'
        wireMock.verify(postRequestedFor(urlEqualTo(POST_COMMENTS_URL))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"ack"'))))
    }

    def "recordProgress delegates to GithubStateWrites, posting a progress marker"() {
        given:
        stubComment()

        when:
        newTracker().recordProgress(ref())

        then: 'the delegated call fired — a dropped void call would post no comment at all'
        wireMock.verify(postRequestedFor(urlEqualTo(POST_COMMENTS_URL))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"progress"'))))
    }
}
