package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * {@link GithubTracker}'s pure delegation wiring (task 4.16): every method forwards to exactly
 * the collaborator that implements it, unmodified. {@code GithubTrackerContractSpec} exercises
 * the read-focused contract properties (feed, claim, markers, fetchTask) against a real WireMock
 * server, but never calls {@code park}/{@code finish}/{@code postNote}/{@code recordAbort} (task
 * 0.2: the contract suite has no direct human-decision-wait dialog to drive these write-only
 * transitions) — this spec proves those delegate correctly, using real collaborators over
 * WireMock (the collaborator classes are {@code final}, so mocking them is not an option; their
 * own behavior is already covered in depth by {@code GithubStateWritesSpec}/{@code
 * GithubCorrespondenceSpec}, whose exact stubbing pattern this spec reuses).
 *
 * <p>Implements FR1, FR4, NFR-R1 of add-tracker-port.
 */
class GithubTrackerSpec extends Specification {

    private static final int ISSUE_NUMBER = 50

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

    private void stubLabelTransition(String removedLabelEncoded) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/labels"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/labels/${removedLabelEncoded}"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
    }

    private void stubComment() {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .willReturn(aResponse().withStatus(201).withBody('{"id":1,"body":"whatever"}')))
    }

    private GithubTracker newTracker() {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def labelOps = new GithubLabelOps(httpClient)
        def cache = new GithubConditionalRequestCache(httpClient)
        new GithubTracker(
                new GithubFeedQuery(cache, 'acme', 'widgets', 'gnomish:ready'),
                new GithubTaskFetcher(httpClient, 'gnomish:working', 'gnomish:needs-human'),
                new GithubClaimLease(httpClient, labelOps, 'gnomish:ready', 'gnomish:working'),
                new GithubStateWrites(httpClient, labelOps, 'gnomish-factory-x7k2q1',
                'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered', 'gnomish:ready'),
                new GithubCorrespondence(httpClient, 'gnomish-factory-x7k2q1'),
                new GithubDecisions(httpClient, 'gnomish-factory-x7k2q1'))
    }

    private TaskRef ref() {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', ISSUE_NUMBER).canonicalId())
    }

    def "park delegates to GithubStateWrites, posting a structural report marker"() {
        given:
        stubLabelTransition('gnomish%3Aworking')
        stubComment()

        when:
        newTracker().park(ref(), ParkReason.CHECKPOINT, 'paused')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"report"'))))
    }

    def "finish delegates to GithubStateWrites, posting a structural report marker"() {
        given:
        stubLabelTransition('gnomish%3Aworking')
        stubComment()

        when:
        newTracker().finish(ref(), 'delivered')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"report"'))))
    }

    def "postNote delegates to GithubCorrespondence, posting a structural note marker"() {
        given:
        stubComment()

        when:
        newTracker().postNote(ref(), 'still working on it')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"note"'))))
    }

    def "release delegates to GithubCorrespondence and makes no HTTP call at all"() {
        when:
        newTracker().release(ref())

        then:
        wireMock.allServeEvents.isEmpty()
    }

    def "recordAbort delegates to GithubStateWrites, posting a structural abort marker"() {
        given:
        stubLabelTransition('gnomish%3Aworking')
        stubComment()
        def record = new AbortRecord('boom', 'gnomish-factory-x7k2q1', Instant.parse('2026-07-20T10:00:00Z'))

        when:
        newTracker().recordAbort(ref(), record)

        then:
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"abort"'))))
    }
}
