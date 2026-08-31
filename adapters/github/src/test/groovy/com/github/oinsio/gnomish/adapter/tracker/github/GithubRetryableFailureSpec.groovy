package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching
import static com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.adapter.github.GithubHttpException
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * FR18 of harden-task-branch-contract: a label-operation failure and a transport failure of a
 * tracker write both classify as the port's retryable tracker-unavailable outage. Neither may
 * surface as a distinct terminal error, because a bounded terminal-write retry only consumes
 * {@link TrackerUnavailableException} — anything else skips the retry budget entirely and fails a
 * transition whose truth marker has usually already landed.
 */
class GithubRetryableFailureSpec extends Specification {

    private static final int ISSUE = 55

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
        // The find half of the find-then-upsert primitive: every marker write reads the thread.
        wireMock.stubFor(get(urlMatching('.*/comments\\?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE}/comments"))
                .willReturn(aResponse().withStatus(201).withBody('{"id":900,"body":"marker"}')))
    }

    def cleanup() {
        wireMock.stop()
    }

    // FR18: the label flip of a terminal write fails with a 5xx the client could not retry away —
    //     the transition must surface as a retryable outage, not as a terminal fault.
    def "a label-operation failure during a terminal write is a retryable tracker outage"() {
        given: 'the truth marker lands, then the label add keeps failing'
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE}/labels"))
                .willReturn(aResponse().withStatus(500)))

        when:
        newStateWrites().park(refFor(ISSUE), ParkReason.ESCALATION, 'needs a decision')

        then:
        def failure = thrown(GithubLabelOpsException)
        failure instanceof TrackerUnavailableException
    }

    // FR18: a connection reset before any response is an unreachable tracker by any reading — the
    //     bounded terminal-write retry must consume it like any other outage.
    def "a transport failure of a tracker write is a retryable tracker outage"() {
        given: 'the comment POST never gets an answer'
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE}/comments"))
                .willReturn(aResponse().withFault(CONNECTION_RESET_BY_PEER)))
        stubLabels()

        when:
        newTracker().park(refFor(ISSUE), ParkReason.ESCALATION, 'needs a decision')

        then:
        def failure = thrown(GithubTransportException)
        failure instanceof TrackerUnavailableException

        and: 'the original transport failure is kept as the cause, not swallowed'
        failure.cause instanceof GithubHttpException
    }

    // A write that succeeds is untouched by the translation: no wrapping, no swallowed outcome.
    def "a successful write passes through the transport translation unchanged"() {
        given:
        stubLabels()

        when:
        newTracker().postNote(refFor(ISSUE), 'a note')

        then:
        noExceptionThrown()
    }

    private GithubTracker newTracker() {
        def httpClient = newHttpClient()
        def labelOps = new GithubLabelOps(httpClient)
        def cache = new GithubConditionalRequestCache(httpClient)
        def labels = new GithubStateLabels('gnomish:ready', 'gnomish:working', 'gnomish:needs-human',
                'gnomish:delivered')
        new GithubTracker(
                new GithubFeedQuery(cache, 'acme', 'widgets', 'gnomish:ready'),
                new GithubTaskFetcher(cache, 'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered'),
                new GithubClaimLease(httpClient, labelOps, 'gnomish:ready', 'gnomish:working'),
                newStateWrites(httpClient, labelOps),
                new GithubCorrespondence(markerWriter(httpClient)),
                new GithubDecisions(httpClient, markerWriter(httpClient)),
                new GithubHeartbeat(httpClient, 'gnomish-factory-a1'),
                new GithubOpenQuery(cache, 'acme', 'widgets', labels),
                new GithubStaleClaimRemoval(httpClient, labelOps, markerWriter(httpClient),
                'gnomish:working', 'gnomish:ready'),
                new GithubIndexRepair(httpClient, labelOps, markerWriter(httpClient), labels))
    }

    private GithubStateWrites newStateWrites(GithubHttpClient httpClient = newHttpClient(),
            GithubLabelOps labelOps = null) {
        def client = httpClient
        def ops = labelOps ?: new GithubLabelOps(client)
        new GithubStateWrites(client, ops, markerWriter(client),
                'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered', 'gnomish:ready')
    }

    private GithubHttpClient newHttpClient() {
        new GithubHttpClient(wireMock.baseUrl(), 'tok', RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build())
    }

    private static GithubMarkerWriter markerWriter(GithubHttpClient httpClient) {
        new GithubMarkerWriter(new GithubCommentUpsert(httpClient), ClaimEpochSource.NONE, 'gnomish-factory-a1')
    }

    private void stubLabels() {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE}/labels"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlMatching("/repos/acme/widgets/issues/${ISSUE}/labels/.*"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
    }

    private TaskRef refFor(int issueNumber) {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', issueNumber).canonicalId())
    }
}
