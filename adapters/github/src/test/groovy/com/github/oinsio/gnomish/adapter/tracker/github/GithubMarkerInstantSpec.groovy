package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.patch
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import spock.lang.Specification

/**
 * FR20, FR21 of supervise-daemon-loops-and-embed-dashboard: every timestamp the GitHub adapter
 * writes into a tracker marker is read from the instant source it was built on. The lease, the
 * heartbeat and the marker writer are built here on one virtual source, as the adapter factory
 * builds them on the root's one source; a component holding a clock of its own would stamp some
 * other instant than the one the spec set.
 */
class GithubMarkerInstantSpec extends Specification {

    private static final String INSTANCE = 'gnomish-factory-x7k2q1'
    private static final String COMMENTS = '/repos/acme/widgets/issues/7/comments'
    private static final Instant START = Instant.parse('2031-03-04T05:06:07Z')

    WireMockServer wireMock
    VirtualClock clock = new VirtualClock(START)
    GithubHttpClient httpClient

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
        httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build())
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/7/labels'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/7/labels/gnomish%3Aready'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(post(urlEqualTo(COMMENTS))
                .willReturn(aResponse().withStatus(201).withBody('{"id":501,"body":"whatever"}')))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/501'))
                .willReturn(aResponse().withStatus(200).withBody('{"id":501,"updated_at":"2031-03-04T05:11:07Z"}')))
        wireMock.stubFor(get(urlEqualTo(COMMENTS + '?per_page=100')).willReturn(aResponse().withStatus(200)
                .withBody('[{"id":501,"updated_at":"2031-03-04T05:06:07Z","created_at":"2031-03-04T05:06:07Z","body":"' + GithubMarker.render(
                        GithubMarkerKind.CLAIM, INSTANCE, START, 'claimed').replace('"', '\\"').replace('\n', '\\n') + '"}]')))
    }

    def cleanup() {
        wireMock.stop()
    }

    private TaskRef ref() {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', 7).canonicalId())
    }

    private void claim() {
        new GithubClaimLease(httpClient, new GithubLabelOps(httpClient), 'gnomish:ready', 'gnomish:working', clock)
                .claim(ref(), INSTANCE)
    }

    private void beat() {
        new GithubHeartbeat(httpClient, INSTANCE, clock).heartbeat(ref(), 'alive')
    }

    private void park() {
        new GithubMarkerWriter(new GithubCommentUpsert(httpClient), ClaimEpochSource.NONE, INSTANCE, clock)
                .write(new GithubTaskId('', 'acme', 'widgets', 7), GithubMarkerKind.PARK, 'need a decision', 'escalation')
    }

    /** The {@code at} stamp of every marker the adapter sent in requests matching {@code pattern}, in order. */
    private List<Instant> stamps(RequestPatternBuilder pattern) {
        wireMock.findAll(pattern).collect {
            GithubMarker.parse(new ObjectMapper().readTree(it.bodyAsString).get('body').asText()).get().at()
        }
    }

    // FR20: the claim marker and the heartbeat marker both read the source the adapter holds.
    // F102: scenario text aligned to what this test asserts
    // Scenario: core/time-source / A marker carries the virtual instant
    def "FR20: a claim marker and a heartbeat marker carry the virtual source's instant at the write"() {
        when: 'a claim is written at the start instant'
        claim()

        then:
        stamps(postRequestedFor(urlEqualTo(COMMENTS))) == [START]

        when: 'the source moves, and a beat is written'
        clock.advance(Duration.ofMinutes(5))
        beat()

        then:
        stamps(patchRequestedFor(urlPathEqualTo('/repos/acme/widgets/issues/comments/501'))) == [clock.instant()]
    }

    // FR21: one virtual source is handed to every stamping component, and one advance moves them
    //     all: the heartbeat and the marker writer stamp the new instant, the earlier claim keeps its.
    // Scenario: core/time-source / One fake drives a mixed flow
    def "FR21: one virtual source, advanced once, moves the lease, the heartbeat and the marker writer together"() {
        given:
        claim()

        when:
        clock.advance(Duration.ofHours(2))
        beat()
        park()

        then: 'the claim stamped the start; the beat and the park both stamp the one advanced instant'
        def moved = START.plus(Duration.ofHours(2))
        stamps(postRequestedFor(urlEqualTo(COMMENTS))) == [START, moved]
        stamps(patchRequestedFor(urlPathEqualTo('/repos/acme/widgets/issues/comments/501'))) == [moved]
    }
}
