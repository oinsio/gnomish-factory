package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.patch
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import static com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.adapter.github.GithubHttpException
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * GithubHeartbeat (add-claim-heartbeat, FR1/FR8, design D1): a beat is an
 * in-place PATCH of the resolved claim comment — the comment id (the lease
 * anchor) stays constant while its body is refreshed with a CLAIM marker
 * carrying the progress line, one write per beat, and the reported {@link
 * ClaimVersion} is (comment id, the PATCH response's updated_at). A 404 on the
 * edit means the claim comment is gone (reaped/taken over) → {@link
 * HeartbeatResult.ClaimGone}; a 5xx (retries exhausted) or a transport failure
 * is an infrastructure failure and throws, never a lost claim.
 *
 * Implements FR1, FR8 of add-claim-heartbeat.
 */
class GithubHeartbeatSpec extends Specification {

    private static final String INSTANCE_ID = 'gnomish-factory-x7k2q1'

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

    private GithubHeartbeat newHeartbeat() {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        new GithubHeartbeat(httpClient, INSTANCE_ID)
    }

    private TaskRef refFor(int issueNumber) {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', issueNumber).canonicalId())
    }

    private static String claimComment(long id, String updatedAt) {
        """{"id":${id},"updated_at":"${updatedAt}","body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-x7k2q1\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"}"""
    }

    def "FR1: beat PATCHes the resolved claim comment in place and reports the refreshed version"() {
        given: 'the claim comment (id 501) is resolvable; the PATCH returns a fresh updated_at'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/60/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200)
                .withBody('[' + claimComment(501, '2026-07-23T10:00:00Z') + ']')))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/501'))
                .willReturn(aResponse().withStatus(200)
                .withBody('{"id":501,"updated_at":"2026-07-23T10:05:00Z","body":"refreshed"}')))

        when:
        def result = newHeartbeat().heartbeat(refFor(60), '🤖 gnomish: stage build, attempt 2, alive 10:05')

        then: 'the version anchors on the stable comment id 501 with the new updated_at'
        result == new HeartbeatResult.Beaten(new ClaimVersion('501', Instant.parse('2026-07-23T10:05:00Z')))

        and: 'exactly one write — the PATCH on the existing comment id — carrying a refreshed CLAIM marker + progress line'
        wireMock.verify(1, patchRequestedFor(urlPathEqualTo('/repos/acme/widgets/issues/comments/501'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"claim"')))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('stage build, attempt 2, alive 10:05'))))
    }

    def "FR1: the anchor comment id is stable across repeated beats"() {
        given: 'two beats against the same claim comment; updated_at advances, the id does not'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/61/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200)
                .withBody('[' + claimComment(777, '2026-07-23T10:00:00Z') + ']')))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/777'))
                .inScenario('beats')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(200)
                .withBody('{"id":777,"updated_at":"2026-07-23T10:05:00Z","body":"b1"}'))
                .willSetStateTo('beat once'))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/777'))
                .inScenario('beats')
                .whenScenarioStateIs('beat once')
                .willReturn(aResponse().withStatus(200)
                .withBody('{"id":777,"updated_at":"2026-07-23T10:10:00Z","body":"b2"}')))
        def heartbeat = newHeartbeat()

        when:
        def first = heartbeat.heartbeat(refFor(61), 'p1')
        def second = heartbeat.heartbeat(refFor(61), 'p2')

        then:
        first == new HeartbeatResult.Beaten(new ClaimVersion('777', Instant.parse('2026-07-23T10:05:00Z')))
        second == new HeartbeatResult.Beaten(new ClaimVersion('777', Instant.parse('2026-07-23T10:10:00Z')))
    }

    def "FR8: a 404 on the beat PATCH means the claim comment is gone, not an infrastructure failure"() {
        given: 'the comment resolves, but a reaper deleted it before the PATCH lands'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/62/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200)
                .withBody('[' + claimComment(502, '2026-07-23T10:00:00Z') + ']')))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/502'))
                .willReturn(aResponse().withStatus(404)))

        when:
        def result = newHeartbeat().heartbeat(refFor(62), 'progress')

        then:
        result == new HeartbeatResult.ClaimGone()
    }

    def "FR8: no resolvable claim comment (thread has none) means the claim is gone"() {
        given: 'the thread carries no live claim marker at all'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/63/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('[]')))

        when:
        def result = newHeartbeat().heartbeat(refFor(63), 'progress')

        then: 'and no PATCH is attempted'
        result == new HeartbeatResult.ClaimGone()
        wireMock.findAll(patchRequestedFor(urlPathEqualTo('/repos/acme/widgets/issues/comments/0'))).isEmpty()
    }

    def "FR8: a persistent 5xx on the beat PATCH is an infrastructure failure and throws (never ClaimGone)"() {
        given: 'the client retries the 5xx and, once exhausted, returns it as a non-2xx the beat throws on'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/64/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200)
                .withBody('[' + claimComment(503, '2026-07-23T10:00:00Z') + ']')))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/503'))
                .willReturn(aResponse().withStatus(503)))

        when:
        newHeartbeat().heartbeat(refFor(64), 'progress')

        then: 'thrown — a 5xx is never mistaken for the 404 claim-gone signal'
        thrown(GithubHeartbeatException)
    }

    def "FR8: a transport failure on the beat PATCH is an infrastructure failure and throws"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/65/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200)
                .withBody('[' + claimComment(504, '2026-07-23T10:00:00Z') + ']')))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/504'))
                .willReturn(aResponse().withFault(CONNECTION_RESET_BY_PEER)))

        when:
        newHeartbeat().heartbeat(refFor(65), 'progress')

        then:
        thrown(GithubHttpException)
    }

    def "a non-404 4xx on the beat PATCH surfaces as GithubHeartbeatException"() {
        given: 'e.g. a 422 the client does not retry and is not a lost-claim signal'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/66/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200)
                .withBody('[' + claimComment(505, '2026-07-23T10:00:00Z') + ']')))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/505'))
                .willReturn(aResponse().withStatus(422)))

        when:
        newHeartbeat().heartbeat(refFor(66), 'progress')

        then:
        thrown(GithubHeartbeatException)
    }

    def "a transport failure on the comment listing is an infrastructure failure and throws"() {
        given: 'the resolve read itself cannot complete; GithubHttpClient exhausts retries and throws infra'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/67/comments?per_page=100'))
                .willReturn(aResponse().withFault(CONNECTION_RESET_BY_PEER)))

        when:
        newHeartbeat().heartbeat(refFor(67), 'progress')

        then:
        thrown(GithubHttpException)
    }

    def "a non-2xx on the comment listing surfaces as GithubHeartbeatException"() {
        given: 'e.g. a 403 the client returns as-is, or an exhausted 5xx'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/68/comments?per_page=100'))
                .willReturn(aResponse().withStatus(403)))

        when:
        newHeartbeat().heartbeat(refFor(68), 'progress')

        then:
        thrown(GithubHeartbeatException)
    }

    def "FR8: a 404 on the comment listing means the issue is gone → ClaimGone (not an infrastructure failure)"() {
        given: 'the issue itself no longer exists, so listing its comments 404s'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/680/comments?per_page=100'))
                .willReturn(aResponse().withStatus(404).withBody('{"message":"Not Found"}')))

        when:
        def result = newHeartbeat().heartbeat(refFor(680), 'progress')

        then: 'the claim is gone with its task — a protocol signal, distinct from a 403/5xx outage'
        result == new HeartbeatResult.ClaimGone()
    }

    def "a malformed beat response body surfaces as GithubHeartbeatException"() {
        given: 'the PATCH succeeds (200) but the body cannot be parsed for updated_at'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/69/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200)
                .withBody('[' + claimComment(506, '2026-07-23T10:00:00Z') + ']')))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/506'))
                .willReturn(aResponse().withStatus(200).withBody('{not json')))

        when:
        newHeartbeat().heartbeat(refFor(69), 'progress')

        then:
        thrown(GithubHeartbeatException)
    }

    def "a beat response body with no updated_at surfaces as GithubHeartbeatException (not NPE)"() {
        given: 'the PATCH succeeds (200) but the body is valid JSON that omits updated_at'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/70/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200)
                .withBody('[' + claimComment(507, '2026-07-23T10:00:00Z') + ']')))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/507'))
                .willReturn(aResponse().withStatus(200).withBody('{}')))

        when:
        newHeartbeat().heartbeat(refFor(70), 'progress')

        then:
        thrown(GithubHeartbeatException)
    }
}
