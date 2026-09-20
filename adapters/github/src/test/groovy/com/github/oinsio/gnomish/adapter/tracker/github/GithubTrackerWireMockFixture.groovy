package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse

/**
 * Shared WireMock/{@link GithubTracker} scaffolding for {@code GithubTrackerSpec} and {@code
 * GithubTrackerDelegationSpec}: both build the same 11-collaborator tracker wiring against the
 * same fake repo (acme/widgets, issue 50) and drive it over an in-process WireMock server.
 * Single owner of that setup so the two specs (split from one file to respect the file-size cap)
 * cannot drift on the wiring under test.
 */
trait GithubTrackerWireMockFixture {

    static final int ISSUE_NUMBER = 50

    static final GithubStateLabels LABELS =
    new GithubStateLabels('gnomish:ready', 'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered')

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
        // The find half of the FR11 find-then-upsert primitive: every factory comment write reads
        // the thread first. Specs that need a populated thread add their own, more recent stub.
        wireMock.stubFor(get(urlMatching('.*/comments\\?per_page=100'))
                .willReturn(aResponse()
                .withStatus(200).withBody('[]')))
    }

    def cleanup() {
        wireMock.stop()
    }

    static RetryConfig fastRetryConfig() {
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

    GithubTracker newTracker() {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def labelOps = new GithubLabelOps(httpClient)
        def cache = new GithubConditionalRequestCache(httpClient)
        // One shared instance, matching GithubTrackerAdapterFactory's production wiring: every
        // non-claim marker write goes through the same GithubMarkerWriter (see its own javadoc).
        def marker = markerWriter(httpClient, 'gnomish-factory-x7k2q1')
        new GithubTracker(
                new GithubFeedQuery(cache, 'acme', 'widgets', 'gnomish:ready'),
                new GithubTaskFetcher(cache, 'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered',
                GithubDesignatorRules.none()),
                new GithubClaimLease(httpClient, labelOps, 'gnomish:ready', 'gnomish:working'),
                new GithubStateWrites(httpClient, labelOps, marker,
                'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered', 'gnomish:ready'),
                new GithubCorrespondence(marker),
                new GithubDecisions(httpClient, marker),
                new GithubHeartbeat(httpClient, 'gnomish-factory-x7k2q1'),
                new GithubOpenQuery(cache, 'acme', 'widgets', LABELS),
                new GithubStaleClaimRemoval(httpClient, labelOps, marker,
                'gnomish:working', 'gnomish:ready'),
                new GithubIndexRepair(httpClient, labelOps, marker, LABELS))
    }

    TaskRef ref() {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', ISSUE_NUMBER).canonicalId())
    }

    void stubLabelTransition(String removedLabelEncoded) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/labels"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/labels/${removedLabelEncoded}"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
    }

    void stubComment() {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .willReturn(aResponse().withStatus(201).withBody('{"id":1,"body":"whatever"}')))
    }

    static GithubMarkerWriter markerWriter(GithubHttpClient httpClient, String instanceId) {
        new GithubMarkerWriter(new GithubCommentUpsert(httpClient), ClaimEpochSource.NONE, instanceId)
    }
}
