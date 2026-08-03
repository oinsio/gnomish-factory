package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER

import com.github.oinsio.gnomish.app.port.tracker.ClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * GithubClaimLease (FR6, NFR-R1 of add-tracker-port, design D13): verifies
 * the lease-claim sequence's happy paths — solo claim wins, the concurrent
 * race is decided by earliest GitHub comment id (WireMock-scripted
 * interleaving), the loser deletes its own claim comment and reports {@code
 * Held(winner)}, and the label point-calls happen as specified.
 *
 * Implements FR6, NFR-R1 of add-tracker-port.
 */
class GithubClaimLeaseSpec extends Specification {

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

    private GithubClaimLease newLease(String readyLabel = 'gnomish:ready',
            String workingLabel = 'gnomish:working') {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def labelOps = new GithubLabelOps(httpClient)
        new GithubClaimLease(httpClient, labelOps, readyLabel, workingLabel)
    }

    private TaskRef refFor(int issueNumber) {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', issueNumber).canonicalId())
    }

    /** Renders one GitHub comment JSON object, JSON-escaping a rendered marker body for embedding in a listing. */
    private static String commentJson(long id, String body) {
        def escaped = body.replace('\\', '\\\\').replace('"', '\\"').replace('\n', '\\n')
        "{\"id\":${id},\"body\":\"${escaped}\"}"
    }

    private static void stubLabelCalls(WireMockServer wireMock, int issueNumber) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels/gnomish%3Aready"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
    }

    def "solo claim: posts the claim comment, sees only its own marker, and reports Acquired"() {
        given:
        stubLabelCalls(wireMock, 20)
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/20/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":501,"body":"whatever"}')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/20/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":501,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-solo\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed by gnomish-factory-solo"}
                        ]
                        ''')))
        def lease = newLease()

        when:
        def result = lease.claim(refFor(20), 'gnomish-factory-solo')

        then:
        result == new ClaimResult.Acquired()
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/20/labels'))
                .withRequestBody(WireMock.equalToJson('{"labels":["gnomish:working"]}')))
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/20/labels/gnomish%3Aready')))
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/20/comments')))
    }

    def "concurrent claim race: the instance whose comment id is earliest wins"() {
        given: 'two instances race on the same issue; instance A posts comment id 601, instance B posts 600'
        stubLabelCalls(wireMock, 21)
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/21/comments'))
                .inScenario('claim race')
                .whenScenarioStateIs('Started')
                .willReturn(aResponse().withStatus(201).withBody('{"id":601,"body":"whatever"}'))
                .willSetStateTo('A posted'))
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/21/comments'))
                .inScenario('claim race')
                .whenScenarioStateIs('A posted')
                .willReturn(aResponse().withStatus(201).withBody('{"id":600,"body":"whatever"}')))
        // Both instances re-read after both comments exist: the earlier id (600, B) wins.
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/21/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":600,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-b\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed by gnomish-factory-b"},
                          {"id":601,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-a\\",\\"at\\":\\"2026-07-23T10:00:01Z\\",\\"version\\":1} -->\\n🤖 claimed by gnomish-factory-a"}
                        ]
                        ''')))
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/comments/601'))
                .willReturn(aResponse().withStatus(204)))
        def leaseA = newLease()
        def leaseB = newLease()

        when: 'instance A claims first (gets the later comment id 601), then instance B (gets 600)'
        def resultA = leaseA.claim(refFor(21), 'gnomish-factory-a')
        def resultB = leaseB.claim(refFor(21), 'gnomish-factory-b')

        then: 'A loses to B (earlier id) and deletes its own marker'
        resultA == new ClaimResult.Held('gnomish-factory-b')
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/601')))

        and: 'B wins, being the earliest id among the boundary-anchored claim markers'
        resultB == new ClaimResult.Acquired()
    }

    def "loser leaves labels as they stand (does not revert the working-label add)"() {
        given:
        stubLabelCalls(wireMock, 22)
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/22/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":700,"body":"whatever"}')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/22/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":650,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-winner\\",\\"at\\":\\"2026-07-23T09:59:00Z\\",\\"version\\":1} -->\\n🤖 claimed by gnomish-factory-winner"},
                          {"id":700,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-loser\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed by gnomish-factory-loser"}
                        ]
                        ''')))
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/comments/700'))
                .willReturn(aResponse().withStatus(204)))
        def lease = newLease()

        when:
        def result = lease.claim(refFor(22), 'gnomish-factory-loser')

        then:
        result == new ClaimResult.Held('gnomish-factory-winner')
        wireMock.verify(1, postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/22/labels')))
        wireMock.verify(1, deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/22/labels/gnomish%3Aready')))
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/700')))
    }

    def "unverifiable claim (persistent 5xx on verify-read) backs out: deletes own comment and throws"() {
        given: 'the claim comment posts fine, but the verify-read list-comments call is always down'
        stubLabelCalls(wireMock, 24)
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/24/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":800,"body":"whatever"}')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/24/comments?per_page=100'))
                .willReturn(aResponse().withStatus(503)))
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/comments/800'))
                .willReturn(aResponse().withStatus(204)))
        def lease = newLease()

        when:
        lease.claim(refFor(24), 'gnomish-factory-unlucky')

        then: 'the retried-and-exhausted verify-read surfaces as a business non-2xx (GithubClaimException)'
        thrown(GithubClaimException)
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/800')))
    }

    def "unverifiable claim (connection failure on verify-read) backs out: deletes own comment and throws"() {
        given: 'the claim comment posts fine, but the verify-read call fails at the transport level'
        stubLabelCalls(wireMock, 25)
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/25/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":801,"body":"whatever"}')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/25/comments?per_page=100'))
                .willReturn(aResponse().withFault(CONNECTION_RESET_BY_PEER)))
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/comments/801'))
                .willReturn(aResponse().withStatus(204)))
        def lease = newLease()

        when:
        lease.claim(refFor(25), 'gnomish-factory-unlucky')

        then: 'GithubHttpClient exhausts retries on the transport failure and throws GithubHttpException'
        thrown(GithubHttpException)
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/801')))
    }

    def "unverifiable claim: the best-effort delete of the own comment failing does not mask the original failure"() {
        given: 'verify-read is persistently down, and so is the compensating delete'
        stubLabelCalls(wireMock, 26)
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/26/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":802,"body":"whatever"}')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/26/comments?per_page=100'))
                .willReturn(aResponse().withStatus(503)))
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/comments/802'))
                .willReturn(aResponse().withFault(CONNECTION_RESET_BY_PEER)))
        def lease = newLease()

        when:
        lease.claim(refFor(26), 'gnomish-factory-unlucky')

        then: 'the original verify-read failure still propagates, not a delete-related exception'
        thrown(GithubClaimException)
    }

    def "boundary-anchors the race to claim markers after the latest abort"() {
        given: 'a stale claim from before an abort must not out-earliest-id the current race'
        stubLabelCalls(wireMock, 23)
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/23/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":900,"body":"whatever"}')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/23/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-stale\\",\\"at\\":\\"2026-07-20T09:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"abort\\",\\"instance\\":\\"gnomish-factory-stale\\",\\"at\\":\\"2026-07-20T10:00:00Z\\",\\"version\\":1} -->\\n🤖 aborted"},
                          {"id":900,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-fresh\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed by gnomish-factory-fresh"}
                        ]
                        ''')))
        def lease = newLease()

        when:
        def result = lease.claim(refFor(23), 'gnomish-factory-fresh')

        then: 'the stale pre-abort claim (id 1) is ignored; the caller is the only post-boundary claim, so it wins'
        result == new ClaimResult.Acquired()
    }

    def "boundary-anchors the race past a park marker so a returned task can be re-claimed (FR6, D13)"() {
        given: 'a held task was parked (a park marker), then returned to ready by a human; the old claim lingers'
        stubLabelCalls(wireMock, 27)
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/27/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":910,"body":"whatever"}')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/27/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-old\\",\\"at\\":\\"2026-07-20T09:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"park\\",\\"instance\\":\\"gnomish-factory-old\\",\\"at\\":\\"2026-07-20T11:00:00Z\\",\\"version\\":1,\\"reason\\":\\"checkpoint\\"} -->\\n🤖 parked for review"},
                          {"id":910,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-fresh\\",\\"at\\":\\"2026-07-24T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed by gnomish-factory-fresh"}
                        ]
                        ''')))
        def lease = newLease()

        when:
        def result = lease.claim(refFor(27), 'gnomish-factory-fresh')

        then: 'the pre-park claim (id 1) is voided by the park boundary; the fresh claim wins instead of Held(old)'
        result == new ClaimResult.Acquired()
    }

    def "boundary-anchors the race past a finish marker so a reopened task can be re-claimed (FR6, D13)"() {
        given: 'a task was finished (a finish marker), then reopened to ready; the old claim lingers'
        stubLabelCalls(wireMock, 28)
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/28/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":920,"body":"whatever"}')))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/28/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":1,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-old\\",\\"at\\":\\"2026-07-20T09:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"},
                          {"id":2,"body":"<!-- gnomish {\\"kind\\":\\"finish\\",\\"instance\\":\\"gnomish-factory-old\\",\\"at\\":\\"2026-07-20T12:00:00Z\\",\\"version\\":1} -->\\n🤖 delivered"},
                          {"id":920,"body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-fresh\\",\\"at\\":\\"2026-07-24T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed by gnomish-factory-fresh"}
                        ]
                        ''')))
        def lease = newLease()

        when:
        def result = lease.claim(refFor(28), 'gnomish-factory-fresh')

        then: 'the pre-finish claim (id 1) is voided by the finish boundary; the fresh claim wins'
        result == new ClaimResult.Acquired()
    }

    def "FR4: boundary-anchors the re-claim past a stale-claim-removed marker so the dead holder's pre-removal claim is ignored (D5)"() {
        given: 'a reaper removed a stale claim but a delete failure left the dead holder CLAIM comment in the thread; a fresh instance now re-claims'
        stubLabelCalls(wireMock, 29)
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/29/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":930,"body":"whatever"}')))
        def deadClaim = commentJson(1, GithubMarker.render(GithubMarkerKind.CLAIM,
                'gnomish-factory-dead', Instant.parse('2026-07-20T09:00:00Z'), '🤖 claimed'))
        def removal = commentJson(2, GithubMarker.render(GithubMarkerKind.STALE_CLAIM_REMOVED,
                'gnomish-factory-dead', Instant.parse('2026-07-24T09:00:00Z'), '🤖 stale claim removed: gnomish-factory-dead'))
        def freshClaim = commentJson(930, GithubMarker.render(GithubMarkerKind.CLAIM,
                'gnomish-factory-fresh', Instant.parse('2026-07-24T10:00:00Z'), '🤖 claimed by gnomish-factory-fresh'))
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/29/comments?per_page=100'))
                .willReturn(aResponse().withStatus(200).withBody("[${deadClaim},${removal},${freshClaim}]")))
        def lease = newLease()

        when:
        def result = lease.claim(refFor(29), 'gnomish-factory-fresh')

        then: 'the dead holder pre-removal claim (id 1) is voided by the removal boundary; the caller is the only post-boundary claim, so it wins instead of Held(dead)'
        result == new ClaimResult.Acquired()
    }
}
