package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * GithubCorrespondence (FR1, FR14 of add-tracker-port): {@code postNote}
 * posts a NOTE-kind structural marker with no label change; {@code release}
 * is an explicit no-op (design D2, FR15 "state untouched") — no HTTP call at
 * all, documented in the class Javadoc.
 *
 * Implements FR1, FR14 of add-tracker-port.
 */
class GithubCorrespondenceSpec extends Specification {

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
        // The find half of the FR11 find-then-upsert primitive: every factory comment write reads
        // the thread first. Specs that need a populated thread add their own, more recent stub.
        wireMock.stubFor(WireMock.get(urlMatching('.*/comments\\?per_page=100'))
                .willReturn(aResponse()
                .withStatus(200).withBody('[]')))
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

    private GithubCorrespondence newCorrespondence(String instanceId = 'gnomish-factory-x7k2q1') {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        new GithubCorrespondence(markerWriter(httpClient, instanceId))
    }

    private TaskRef refFor(int issueNumber) {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', issueNumber).canonicalId())
    }

    def "postNote posts a NOTE marker with the given text and touches no label endpoint"() {
        given:
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/50/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":1,"body":"whatever"}')))
        def correspondence = newCorrespondence()

        when:
        correspondence.postNote(refFor(50), 'Work stopped: task revoked mid-round.')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/50/comments'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"note"')))
                .withRequestBody(WireMock.matchingJsonPath(
                        '$.body', WireMock.containing('Work stopped: task revoked mid-round.'))))
        wireMock.verify(0, postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/50/labels')))
    }

    def "postNote failing to post surfaces as GithubStateWriteException"() {
        given:
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/51/comments'))
                .willReturn(aResponse().withStatus(500)))
        def correspondence = newCorrespondence()

        when:
        correspondence.postNote(refFor(51), 'Note text.')

        then:
        thrown(GithubStateWriteException)
    }

    def "release is a no-op: no HTTP call happens at all"() {
        given:
        def correspondence = newCorrespondence()

        when:
        correspondence.release(refFor(52))

        then:
        wireMock.verify(0, anyRequestedFor(urlMatching('.*')))
    }

    private static GithubMarkerWriter markerWriter(GithubHttpClient httpClient, String instanceId) {
        new GithubMarkerWriter(new GithubCommentUpsert(httpClient), ClaimEpochSource.NONE, instanceId)
    }
}
