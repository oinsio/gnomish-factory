package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.patch
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.tomakehurst.wiremock.WireMockServer
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * GithubMarkerWriter (FR11, FR13, UX3 of harden-task-branch-contract, design D7):
 * the one renderer every factory marker is written through — it stamps the tenure's
 * claim epoch, derives the content identity, and upserts.
 *
 * FR13: every tracker write of a tenure carries that tenure's epoch.
 * FR11/UX3: a re-driven write of the same tenure updates its own comment; the next
 * tenure's write is a new comment, which is what keeps boundary markers in position.
 */
class GithubMarkerWriterSpec extends Specification {

    private static final String COMMENTS = '/repos/acme/widgets/issues/42/comments'
    private static final String LIST = COMMENTS + '?per_page=100'

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
        wireMock.stubFor(post(urlEqualTo(COMMENTS))
                .willReturn(aResponse().withStatus(201).withBody('{"id":700}')))
        wireMock.stubFor(patch(urlMatching('/repos/acme/widgets/issues/comments/\\d+'))
                .willReturn(aResponse().withStatus(200).withBody('{"id":9}')))
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

    private static GithubTaskId taskId() {
        new GithubTaskId('', 'acme', 'widgets', 42)
    }

    private GithubMarkerWriter writerHolding(ClaimEpoch epoch) {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def source = epoch == null
                ? ClaimEpochSource.NONE
                : { String taskId -> Optional.of(epoch) } as ClaimEpochSource
        new GithubMarkerWriter(new GithubCommentUpsert(httpClient), source, 'gnomish-factory-x7k2q1')
    }

    private void stubThread(List<Map> comments) {
        def payload = new ObjectMapper().writeValueAsString(comments.collect {
            [id: it.id, body: it.body, created_at: '2026-07-20T12:00:00Z']
        })
        wireMock.stubFor(get(urlEqualTo(LIST)).willReturn(aResponse().withStatus(200).withBody(payload)))
    }

    private static String postedBody(WireMockServer wireMock, String url) {
        def request = wireMock.findAll(postRequestedFor(urlEqualTo(url))).first()
        new ObjectMapper().readTree(request.bodyAsString).get('body').asText()
    }

    def "stamps the tenure's claim epoch into the marker it writes (FR13)"() {
        given:
        stubThread([])

        when:
        writerHolding(new ClaimEpoch(1234)).write(taskId(), GithubMarkerKind.PARK, 'need a decision', 'escalation')

        then:
        def parsed = GithubMarker.parse(postedBody(wireMock, COMMENTS)).get()
        parsed.epoch() == new ClaimEpoch(1234)
        parsed.identity() == new GithubCommentIdentity('acme/widgets#42', 'park@1234')
        parsed.reason() == 'escalation'
    }

    def "a re-driven write of the same tenure updates its own comment, adding no duplicate (UX3)"() {
        given: 'the tenure already parked once and the instance is re-driving that same park'
        def writer = writerHolding(new ClaimEpoch(1234))
        stubThread([
            [id: 9, body: GithubMarker.render(GithubMarkerKind.PARK, 'gnomish-factory-x7k2q1',
                Instant.parse('2026-07-20T12:00:00Z'), 'need a decision', 'escalation',
                new GithubCommentIdentity('acme/widgets#42', 'park@1234'), new ClaimEpoch(1234))]
        ])

        when:
        def landedOn = writer.write(taskId(), GithubMarkerKind.PARK, 'need a decision (re-driven)', 'escalation')

        then:
        landedOn == 9
        wireMock.verify(0, postRequestedFor(urlEqualTo(COMMENTS)))
        wireMock.verify(patchRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/9')))
    }

    def "the next tenure's write is a new comment, so a boundary marker never moves backwards"() {
        given: 'the previous tenure parked; this instance now holds a later tenure'
        stubThread([
            [id: 9, body: GithubMarker.render(GithubMarkerKind.PARK, 'gnomish-factory-old',
                Instant.parse('2026-07-20T12:00:00Z'), 'first park', 'escalation',
                new GithubCommentIdentity('acme/widgets#42', 'park@1234'), new ClaimEpoch(1234))]
        ])

        when:
        writerHolding(new ClaimEpoch(9999)).write(taskId(), GithubMarkerKind.PARK, 'second park', 'escalation')

        then:
        wireMock.verify(1, postRequestedFor(urlEqualTo(COMMENTS)))
        wireMock.verify(0, patchRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/9')))
        GithubMarker.parse(postedBody(wireMock, COMMENTS)).get().identity().intent() == 'park@9999'
    }

    def "a claimless write scopes its identity by its own text and stamps no epoch"() {
        given:
        stubThread([])

        when:
        writerHolding(null).write(taskId(), GithubMarkerKind.NOTE, 'work stopped: task revoked', null)

        then:
        def parsed = GithubMarker.parse(postedBody(wireMock, COMMENTS)).get()
        parsed.epoch() == null
        parsed.identity().intent().startsWith('note@')
        parsed.identity().intent() != 'note@'
    }

    def "two different claimless notes are two different comments, the same note is one"() {
        given:
        def writer = writerHolding(null)
        stubThread([])

        when:
        writer.write(taskId(), GithubMarkerKind.NOTE, 'first note', null)
        def sameAsFirst = GithubMarker.parse(postedBody(wireMock, COMMENTS)).get().identity()

        and:
        wireMock.resetRequests()
        writer.write(taskId(), GithubMarkerKind.NOTE, 'a different note', null)
        def second = GithubMarker.parse(postedBody(wireMock, COMMENTS)).get().identity()

        then:
        sameAsFirst != second
        sameAsFirst == GithubCommentIdentity.of(taskId(), 'note@' + Integer.toHexString('first note'.hashCode()))
    }

    def "an explicitly scoped write records the author and time it is given, not the writer's own"() {
        given:
        stubThread([])
        def abortedAt = Instant.parse('2026-07-19T08:00:00Z')

        when:
        writerHolding(new ClaimEpoch(1234)).write(taskId(), new GithubMarkerWrite(GithubMarkerKind.ABORT, '1234',
                '🤖 gnomish: aborted: build failed', null, new ClaimEpoch(1234), 'gnomish-factory-other', abortedAt))

        then:
        def parsed = GithubMarker.parse(postedBody(wireMock, COMMENTS)).get()
        parsed.instance() == 'gnomish-factory-other'
        parsed.at() == abortedAt
        parsed.identity().intent() == 'abort@1234'
    }
}
