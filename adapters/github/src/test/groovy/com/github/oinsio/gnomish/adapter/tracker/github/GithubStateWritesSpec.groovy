package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
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
 * GithubStateWrites (FR14, FR18 of add-tracker-port; design D13):
 * {@code park} point-transitions working -> needs-human and posts a
 * PARK-kind marker carrying the park reason; {@code finish}
 * point-transitions working -> delivered and posts a FINISH-kind
 * marker; {@code recordAbort} posts an ABORT-kind marker AND
 * point-transitions working -> ready, as one operation. {@code recordProgress}
 * posts a PROGRESS-kind marker only, with no label transition at all
 * (fix-abort-progress-reset design D3).
 *
 * Implements FR14, FR18 of add-tracker-port; FR1, FR4 of fix-abort-progress-reset;
 * FR1, FR2 of enforce-finish-terminality.
 */
class GithubStateWritesSpec extends Specification {

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

    private GithubStateWrites newWrites(String instanceId = 'gnomish-factory-x7k2q1') {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def labelOps = new GithubLabelOps(httpClient)
        new GithubStateWrites(httpClient, labelOps, instanceId,
                'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered', 'gnomish:ready')
    }

    private TaskRef refFor(int issueNumber) {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', issueNumber).canonicalId())
    }

    private static void stubLabelTransition(WireMockServer wireMock, int issueNumber, String removedLabelEncoded) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels/${removedLabelEncoded}"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
    }

    private static void stubComment(WireMockServer wireMock, int issueNumber) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments"))
                .willReturn(aResponse().withStatus(201).withBody('{"id":1,"body":"whatever"}')))
    }

    private static void stubIssue(WireMockServer wireMock, int issueNumber, List<String> labelNames) {
        def labelsJson = labelNames.collect {
            '{"name":"' + it + '"}'
        }.join(',')
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}"))
                .willReturn(aResponse().withStatus(200)
                .withBody('{"title":"t","body":"b","state":"open","labels":[' + labelsJson + ']}')))
    }

    def "park transitions working to needs-human and posts a PARK marker carrying the reason"() {
        given:
        stubLabelTransition(wireMock, 40, 'gnomish%3Aworking')
        stubComment(wireMock, 40)
        def writes = newWrites()

        when:
        writes.park(refFor(40), ParkReason.ESCALATION, 'Need a decision on approach.')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/40/labels'))
                .withRequestBody(WireMock.equalToJson('{"labels":["gnomish:needs-human"]}')))
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/40/labels/gnomish%3Aworking')))
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/40/comments'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"park"')))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"reason":"escalation"')))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('Need a decision on approach.'))))
    }

    def "finish transitions working to delivered and posts a FINISH comment with the summary"() {
        given:
        stubLabelTransition(wireMock, 41, 'gnomish%3Aworking')
        stubComment(wireMock, 41)
        def writes = newWrites()

        when:
        writes.finish(refFor(41), 'All stages passed. Branch: gnomish/task-41.')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/41/labels'))
                .withRequestBody(WireMock.equalToJson('{"labels":["gnomish:delivered"]}')))
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/41/labels/gnomish%3Aworking')))
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/41/comments'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"finish"')))
                .withRequestBody(WireMock.matchingJsonPath(
                        '$.body', WireMock.notMatching('.*"reason".*')))
                .withRequestBody(WireMock.matchingJsonPath(
                        '$.body', WireMock.containing('All stages passed. Branch: gnomish/task-41.'))))
    }

    def "recordAbort posts an ABORT marker and transitions working back to ready, as one operation"() {
        given:
        stubLabelTransition(wireMock, 42, 'gnomish%3Aworking')
        stubComment(wireMock, 42)
        def writes = newWrites()
        def record = new AbortRecord('agent CLI crashed', 'gnomish-factory-a', Instant.parse('2026-07-23T10:00:00Z'))

        when:
        writes.recordAbort(refFor(42), record)

        then:
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/42/labels'))
                .withRequestBody(WireMock.equalToJson('{"labels":["gnomish:ready"]}')))
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/42/labels/gnomish%3Aworking')))
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/42/comments'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"abort"')))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"instance":"gnomish-factory-a"')))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('agent CLI crashed'))))
    }

    def "recordProgress posts a PROGRESS marker with no label transition"() {
        given:
        stubComment(wireMock, 44)
        def writes = newWrites()

        when:
        writes.recordProgress(refFor(44))

        then:
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/44/comments'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"progress"')))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"instance":"gnomish-factory-x7k2q1"'))))
        wireMock.findAll(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/44/labels'))).isEmpty()
    }

    def "park failing to post the comment surfaces as GithubStateWriteException"() {
        given:
        stubLabelTransition(wireMock, 43, 'gnomish%3Aworking')
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/43/comments'))
                .willReturn(aResponse().withStatus(500)))
        def writes = newWrites()

        when:
        writes.park(refFor(43), ParkReason.INFRA, 'Environment broken.')

        then:
        thrown(GithubStateWriteException)
    }

    def "declineFinished restores delivered and posts a NOTE marker explaining the decline"() {
        given:
        stubIssue(wireMock, 50, ['gnomish:ready'])
        stubLabelTransition(wireMock, 50, 'gnomish%3Aready')
        stubComment(wireMock, 50)
        def writes = newWrites()

        when:
        writes.declineFinished(refFor(50), 'This task is already finished. Please open a new task or bug.')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/50/labels'))
                .withRequestBody(WireMock.equalToJson('{"labels":["gnomish:delivered"]}')))
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/50/labels/gnomish%3Aready')))
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/50/comments'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"note"')))
                .withRequestBody(WireMock.matchingJsonPath(
                        '$.body', WireMock.containing('This task is already finished. Please open a new task or bug.'))))
    }

    def "declineFinished on an already-delivered issue is a silent no-op"() {
        given:
        stubIssue(wireMock, 51, ['gnomish:delivered'])
        def writes = newWrites()

        when:
        writes.declineFinished(refFor(51), 'This task is already finished. Please open a new task or bug.')

        then:
        wireMock.verify(0, postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/51/labels')))
        wireMock.verify(0, deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/51/labels/gnomish%3Aready')))
        wireMock.verify(0, postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/51/comments')))
        wireMock.verify(getRequestedFor(urlEqualTo('/repos/acme/widgets/issues/51')))
    }

    def "declineFinished never posts the comment when the label transition fails"() {
        given:
        stubIssue(wireMock, 52, ['gnomish:ready'])
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/52/labels'))
                .willReturn(aResponse().withStatus(500)))
        def writes = newWrites()

        when:
        writes.declineFinished(refFor(52), 'This task is already finished. Please open a new task or bug.')

        then:
        thrown(GithubLabelOpsException)
        wireMock.verify(0, postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/52/comments')))
    }
}
