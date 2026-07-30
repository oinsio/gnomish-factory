package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import static com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER

import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * GithubStaleClaimRemoval (add-claim-heartbeat, FR4/FR5, design D5/D12): a
 * version-guarded reap. The pre-action re-check reads the thread FRESH (a direct
 * list-comments GET, not the ETag cache) and compares the live claim's (comment
 * id, updated_at) against the caller's observed version. On a match it posts the
 * stale-claim-removed boundary marker FIRST, deletes the dead claim comment, then
 * flips the working label back to ready — never claiming the task for the caller.
 * On any mismatch (beaten, replaced, already gone) it is a safe no-op reporting
 * the live version (or null when the claim is already gone), which is what makes
 * concurrent removals converge.
 *
 * Implements FR4, FR5 of add-claim-heartbeat.
 */
class GithubStaleClaimRemovalSpec extends Specification {

    private static final String INSTANCE_ID = 'gnomish-factory-reaper'
    private static final String WORKING_LABEL = 'gnomish:working'
    private static final String READY_LABEL = 'gnomish:ready'

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

    private GithubStaleClaimRemoval newRemoval() {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        new GithubStaleClaimRemoval(httpClient, new GithubLabelOps(httpClient), INSTANCE_ID, WORKING_LABEL, READY_LABEL)
    }

    private TaskRef refFor(int issueNumber) {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', issueNumber).canonicalId())
    }

    private static String claimComment(long id, String holder, String updatedAt) {
        """{"id":${id},"updated_at":"${updatedAt}","body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"${holder}\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"}"""
    }

    private void stubComments(int issueNumber, String body) {
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody(body)))
    }

    private void stubLabelTransition(int issueNumber) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels/gnomish%3Aworking"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
    }

    private void stubMarkerPost(int issueNumber) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments"))
                .willReturn(aResponse().withStatus(201).withBody('{"id":9001,"body":"marker"}')))
    }

    def "FR4: a matching observed version removes the claim — marker, delete, label flip — and returns Removed"() {
        given: 'the live claim (id 501, held by the dead instance) matches the observed version'
        stubComments(70, '[' + claimComment(501, 'gnomish-factory-dead', '2026-07-23T10:00:00Z') + ']')
        stubMarkerPost(70)
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/comments/501'))
                .willReturn(aResponse().withStatus(204)))
        stubLabelTransition(70)

        when:
        def result = newRemoval().removeStaleClaim(refFor(70),
                new ClaimVersion('501', Instant.parse('2026-07-23T10:00:00Z')))

        then: 'the task is returned to circulation, the caller does not hold it'
        result instanceof RemoveStaleClaimResult.Removed

        and: 'the stale-claim-removed boundary marker names the dead holder, removed id and observed version'
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/70/comments'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"stale_claim_removed"')))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('dead holder gnomish-factory-dead')))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('removed comment 501'))))

        and: 'the dead claim comment is deleted and the working label flips to ready'
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/501')))
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/70/labels'))
                .withRequestBody(WireMock.containing(READY_LABEL)))
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/70/labels/gnomish%3Aworking')))
    }

    def "FR5: a beaten claim (updated_at advanced) is a no-op reporting the live version"() {
        given: 'the holder beat the claim between observation and removal — same id, newer updated_at'
        stubComments(71, '[' + claimComment(502, 'gnomish-factory-dead', '2026-07-23T10:30:00Z') + ']')

        when: 'the caller observed the OLD updated_at'
        def result = newRemoval().removeStaleClaim(refFor(71),
                new ClaimVersion('502', Instant.parse('2026-07-23T10:00:00Z')))

        then: 'nothing is removed; the live version is reported'
        result == new RemoveStaleClaimResult.Mismatch(
                new ClaimVersion('502', Instant.parse('2026-07-23T10:30:00Z')))

        and: 'no marker, delete or label call happened'
        wireMock.findAll(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/71/comments'))).isEmpty()
        wireMock.findAll(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/502'))).isEmpty()
    }

    def "FR5: a replaced claim (different comment id) is a no-op reporting the new live version"() {
        given: 'the observed claim is gone and a different one (id 777) now holds the task'
        stubComments(72, '[' + claimComment(777, 'gnomish-factory-new', '2026-07-23T11:00:00Z') + ']')

        when:
        def result = newRemoval().removeStaleClaim(refFor(72),
                new ClaimVersion('502', Instant.parse('2026-07-23T10:00:00Z')))

        then:
        result == new RemoveStaleClaimResult.Mismatch(
                new ClaimVersion('777', Instant.parse('2026-07-23T11:00:00Z')))
    }

    def "FR4: racing convergence — the claim is already gone, so removal is a no-op reporting a null version"() {
        given: 'a second remover re-reads and finds the claim comment already deleted (only the removal boundary remains)'
        stubComments(73, '''
                [
                  {"id":900,"updated_at":"2026-07-23T10:10:00Z","body":"<!-- gnomish {\\"kind\\":\\"stale_claim_removed\\",\\"instance\\":\\"gnomish-factory-first\\",\\"at\\":\\"2026-07-23T10:10:00Z\\",\\"version\\":1} -->\\n🤖 removed"}
                ]
                ''')

        when:
        def result = newRemoval().removeStaleClaim(refFor(73),
                new ClaimVersion('501', Instant.parse('2026-07-23T10:00:00Z')))

        then: 'no-op with a null current version — the claim marker is already gone'
        result == new RemoveStaleClaimResult.Mismatch(null)

        and: 'no removal side effects are attempted'
        wireMock.findAll(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/73/comments'))).isEmpty()
    }

    def "FR4: racing convergence — a 404 on the dead-comment delete is harmless and the removal still completes"() {
        given: 'between our re-check and delete, a racer deleted the same comment — DELETE returns 404'
        stubComments(74, '[' + claimComment(503, 'gnomish-factory-dead', '2026-07-23T10:00:00Z') + ']')
        stubMarkerPost(74)
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/comments/503'))
                .willReturn(aResponse().withStatus(404)))
        stubLabelTransition(74)

        when:
        def result = newRemoval().removeStaleClaim(refFor(74),
                new ClaimVersion('503', Instant.parse('2026-07-23T10:00:00Z')))

        then: 'the 404 is tolerated; the label still flips and Removed is returned'
        result instanceof RemoveStaleClaimResult.Removed
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/74/labels'))
                .withRequestBody(WireMock.containing(READY_LABEL)))
    }

    def "an empty thread (no claim at all) is a no-op reporting a null version"() {
        given:
        stubComments(75, '[]')

        when:
        def result = newRemoval().removeStaleClaim(refFor(75),
                new ClaimVersion('501', Instant.parse('2026-07-23T10:00:00Z')))

        then:
        result == new RemoveStaleClaimResult.Mismatch(null)
    }

    def "a non-2xx on the pre-action re-read surfaces as GithubStaleClaimException"() {
        given: 'e.g. a 403 the client returns as-is'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/76/comments?per_page=100'))
                .willReturn(aResponse().withStatus(403)))

        when:
        newRemoval().removeStaleClaim(refFor(76),
                new ClaimVersion('501', Instant.parse('2026-07-23T10:00:00Z')))

        then:
        thrown(GithubStaleClaimException)
    }

    def "FR4: a 404 on the pre-action re-read means the issue is gone → Mismatch(null) (not an infrastructure failure)"() {
        given: 'the issue itself no longer exists, so re-reading its comments 404s'
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/760/comments?per_page=100'))
                .willReturn(aResponse().withStatus(404).withBody('{"message":"Not Found"}')))

        when:
        def result = newRemoval().removeStaleClaim(refFor(760),
                new ClaimVersion('501', Instant.parse('2026-07-23T10:00:00Z')))

        then: 'the claim is gone with its task — a safe no-op reporting a null version, distinct from a 403/5xx outage'
        result == new RemoveStaleClaimResult.Mismatch(null)

        and: 'nothing is written: no marker, delete, or label call'
        wireMock.findAll(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/760/comments'))).isEmpty()
    }

    def "a transport failure on the pre-action re-read is an infrastructure failure and throws"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/77/comments?per_page=100'))
                .willReturn(aResponse().withFault(CONNECTION_RESET_BY_PEER)))

        when:
        newRemoval().removeStaleClaim(refFor(77),
                new ClaimVersion('501', Instant.parse('2026-07-23T10:00:00Z')))

        then:
        thrown(GithubHttpException)
    }

    def "a non-2xx on the marker POST surfaces as GithubStaleClaimException (boundary must land)"() {
        given: 'the version matches but the boundary marker POST fails; delete must not have run'
        stubComments(78, '[' + claimComment(504, 'gnomish-factory-dead', '2026-07-23T10:00:00Z') + ']')
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/78/comments'))
                .willReturn(aResponse().withStatus(422)))

        when:
        newRemoval().removeStaleClaim(refFor(78),
                new ClaimVersion('504', Instant.parse('2026-07-23T10:00:00Z')))

        then: 'the marker is posted FIRST, so its failure aborts before any delete'
        thrown(GithubStaleClaimException)
        wireMock.findAll(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/comments/504'))).isEmpty()
    }

    def "a non-404 error on the dead-comment delete surfaces as GithubStaleClaimException"() {
        given:
        stubComments(79, '[' + claimComment(505, 'gnomish-factory-dead', '2026-07-23T10:00:00Z') + ']')
        stubMarkerPost(79)
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/comments/505'))
                .willReturn(aResponse().withStatus(403)))

        when:
        newRemoval().removeStaleClaim(refFor(79),
                new ClaimVersion('505', Instant.parse('2026-07-23T10:00:00Z')))

        then:
        thrown(GithubStaleClaimException)
    }
}
