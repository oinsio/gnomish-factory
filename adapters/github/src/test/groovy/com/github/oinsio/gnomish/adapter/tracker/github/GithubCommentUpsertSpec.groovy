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
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * GithubCommentUpsert (FR11, UX3 of harden-task-branch-contract, design D7):
 * the one find-then-upsert primitive every factory comment is written
 * through — find by hidden content identity, PATCH in place when found, POST
 * only when not.
 *
 * FR11: no factory write path posts blind.
 * UX3: a crash-retry updates instead of duplicating, whichever instance re-drives it.
 */
class GithubCommentUpsertSpec extends Specification {

    private static final String COMMENTS = '/repos/acme/widgets/issues/42/comments'
    private static final String LIST = COMMENTS + '?per_page=100'

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

    private GithubCommentUpsert newUpsert() {
        new GithubCommentUpsert(new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig()))
    }

    private static GithubTaskId taskId() {
        new GithubTaskId('', 'acme', 'widgets', 42)
    }

    private static GithubCommentIdentity identity(String intent = 'park') {
        GithubCommentIdentity.of(taskId(), intent)
    }

    private static String bodyFor(GithubCommentIdentity id, String text) {
        GithubMarker.render(GithubMarkerKind.PARK, 'gnomish-factory-x7k2q1',
                Instant.parse('2026-07-20T12:00:00Z'), text, 'escalation', id, null)
    }

    private void stubThread(List<Map> comments) {
        def payload = new ObjectMapper().writeValueAsString(comments.collect {
            [id: it.id, body: it.body, created_at: '2026-07-20T12:00:00Z']
        })
        wireMock.stubFor(get(urlEqualTo(LIST)).willReturn(aResponse().withStatus(200).withBody(payload)))
    }

    def "posts a new comment when the thread carries no comment of that identity"() {
        given:
        stubThread([
            [id: 5, body: 'a human reply'],
            [id: 6, body: bodyFor(identity('finish'), 'other intent')]
        ])
        wireMock.stubFor(post(urlEqualTo(COMMENTS))
                .willReturn(aResponse().withStatus(201).withBody('{"id":77}')))

        when:
        def landedOn = newUpsert().upsert(taskId(), identity(), bodyFor(identity(), 'parked: need a decision'))

        then:
        landedOn == 77
        wireMock.verify(1, postRequestedFor(urlEqualTo(COMMENTS)))
        wireMock.verify(0, patchRequestedFor(urlMatching('/repos/acme/widgets/issues/comments/.*')))
    }

    def "updates in place and posts nothing when the identity is already on the thread (UX3)"() {
        given:
        stubThread([
            [id: 9, body: bodyFor(identity(), 'parked: first attempt')]
        ])
        wireMock.stubFor(patch(urlEqualTo('/repos/acme/widgets/issues/comments/9'))
                .willReturn(aResponse().withStatus(200).withBody('{"id":9}')))

        when:
        def landedOn = newUpsert().upsert(taskId(), identity(), bodyFor(identity(), 'parked: re-driven'))

        then:
        landedOn == 9
        wireMock.verify(0, postRequestedFor(urlEqualTo(COMMENTS)))
        wireMock.verify(patchRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/9'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('parked: re-driven'))))
    }

    def "matches on content identity, not on the instance that posted it"() {
        given: 'the existing comment was posted by a different factory instance'
        def foreign = GithubMarker.render(GithubMarkerKind.PARK, 'gnomish-factory-other',
                Instant.parse('2026-07-20T11:00:00Z'), 'parked by another instance', 'escalation', identity(), null)
        stubThread([[id: 11, body: foreign]])
        wireMock.stubFor(patch(urlEqualTo('/repos/acme/widgets/issues/comments/11'))
                .willReturn(aResponse().withStatus(200).withBody('{"id":11}')))

        when:
        def landedOn = newUpsert().upsert(taskId(), identity(), bodyFor(identity(), 're-delivered'))

        then:
        landedOn == 11
        wireMock.verify(0, postRequestedFor(urlEqualTo(COMMENTS)))
    }

    def "converges on the earliest matching comment when the thread carries two"() {
        given:
        stubThread([
            [id: 3, body: bodyFor(identity(), 'first')],
            [id: 8, body: bodyFor(identity(), 'duplicate from before the contract')]
        ])
        wireMock.stubFor(patch(urlMatching('/repos/acme/widgets/issues/comments/\\d+'))
                .willReturn(aResponse().withStatus(200).withBody('{"id":3}')))

        when:
        def landedOn = newUpsert().upsert(taskId(), identity(), bodyFor(identity(), 'converged'))

        then:
        landedOn == 3
        wireMock.verify(patchRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/3')))
        wireMock.verify(0, patchRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/8')))
    }

    def "a marker written before the contract carries no identity and never matches"() {
        given:
        def preContract = GithubMarker.render(GithubMarkerKind.PARK, 'gnomish-factory-x7k2q1',
                Instant.parse('2026-07-20T10:00:00Z'), 'legacy park', 'escalation')
        stubThread([[id: 4, body: preContract]])
        wireMock.stubFor(post(urlEqualTo(COMMENTS))
                .willReturn(aResponse().withStatus(201).withBody('{"id":90}')))

        when:
        def landedOn = newUpsert().upsert(taskId(), identity(), bodyFor(identity(), 'parked'))

        then:
        landedOn == 90
        wireMock.verify(1, postRequestedFor(urlEqualTo(COMMENTS)))
    }

    def "a failed post surfaces as a retryable tracker-unavailable failure (FR18)"() {
        given:
        stubThread([])
        wireMock.stubFor(post(urlEqualTo(COMMENTS)).willReturn(aResponse().withStatus(422)))

        when:
        newUpsert().upsert(taskId(), identity(), bodyFor(identity(), 'parked'))

        then:
        def e = thrown(GithubStateWriteException)
        e.message.contains('post factory comment')
    }

    def "a failed update surfaces as a retryable tracker-unavailable failure (FR18)"() {
        given:
        stubThread([
            [id: 9, body: bodyFor(identity(), 'existing')]
        ])
        wireMock.stubFor(patch(urlEqualTo('/repos/acme/widgets/issues/comments/9'))
                .willReturn(aResponse().withStatus(422)))

        when:
        newUpsert().upsert(taskId(), identity(), bodyFor(identity(), 'parked'))

        then:
        def e = thrown(GithubStateWriteException)
        e.message.contains('update factory comment')
    }

    def "an unparseable create response surfaces as a tracker-unavailable failure"() {
        given:
        stubThread([])
        wireMock.stubFor(post(urlEqualTo(COMMENTS))
                .willReturn(aResponse().withStatus(201).withBody('not json')))

        when:
        newUpsert().upsert(taskId(), identity(), bodyFor(identity(), 'parked'))

        then:
        def e = thrown(GithubStateWriteException)
        e.message.contains('parse created comment response')
    }
}
