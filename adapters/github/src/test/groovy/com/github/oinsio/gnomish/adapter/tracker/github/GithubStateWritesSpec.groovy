package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
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
        // The find half of the FR11 find-then-upsert primitive: every factory comment write reads
        // the thread first. Specs that need a populated thread add their own, more recent stub.
        wireMock.stubFor(get(WireMock.urlMatching('.*/comments\\?per_page=100'))
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

    private GithubStateWrites newWrites(String instanceId = 'gnomish-factory-x7k2q1') {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def labelOps = new GithubLabelOps(httpClient)
        new GithubStateWrites(httpClient, labelOps, markerWriter(httpClient, instanceId),
                'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered', 'gnomish:ready')
    }

    /** A writer that holds the given tenure on every task, for the epoch-stamping scenarios. */
    private GithubStateWrites newWritesHolding(long epoch) {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def labelOps = new GithubLabelOps(httpClient)
        def source = { String taskId ->
            Optional.of(new ClaimEpoch(epoch))
        } as ClaimEpochSource
        def writer = new GithubMarkerWriter(
                new GithubCommentUpsert(httpClient), source, 'gnomish-factory-x7k2q1')
        new GithubStateWrites(httpClient, labelOps, writer,
                'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered', 'gnomish:ready')
    }

    private String postedMarkerOn(int issueNumber) {
        def request = wireMock.findAll(postRequestedFor(
                        urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments"))).first()
        new ObjectMapper()
                .readTree(request.bodyAsString).get('body').asText()
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

    /** Stubs the label POST as a persistent 5xx: the flip half of a sequence that never completes. */
    private static void stubFailingLabelAdd(WireMockServer wireMock, int issueNumber) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels"))
                .willReturn(aResponse().withStatus(500).withBody('{"message":"boom"}')))
    }

    // FR12 of harden-task-branch-contract: markers are the truth, labels the index. A kill (here, a
    // failing label API) between the two must leave the recorded fact on the thread — the lagging
    // index the sweep repairs — never a flipped label with no record of why.
    def "#operation posts its truth marker before the label flip that indexes it"() {
        given:
        stubFailingLabelAdd(wireMock, issueNumber)
        stubComment(wireMock, issueNumber)
        def writes = newWrites()

        when:
        write(writes, refFor(issueNumber))

        then: 'the flip fails, but the marker is already on the thread'
        thrown(Exception)
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"' + kind + '"'))))

        and: 'the working label is still in place — nothing was removed behind the unrecorded flip'
        wireMock.verify(0, deleteRequestedFor(
                        urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels/gnomish%3Aworking")))

        where:
        operation | issueNumber | kind | write
        'park' | 60 | 'park' | { GithubStateWrites w, TaskRef ref ->
            w.park(ref, ParkReason.ESCALATION, 'report')
        }
        'finish' | 61 | 'finish' | { GithubStateWrites w, TaskRef ref ->
            w.finish(ref, 'summary')
        }
        'recordAbort' | 62 | 'abort' | { GithubStateWrites w, TaskRef ref ->
            w.recordAbort(ref, new AbortRecord('crashed', 'gnomish-factory-a', Instant.parse('2026-07-23T10:00:00Z')))
        }
    }

    private static GithubMarkerWriter markerWriter(httpClient, String instanceId) {
        new GithubMarkerWriter(new GithubCommentUpsert(httpClient), ClaimEpochSource.NONE, instanceId)
    }

    def "an abort of a live tenure is scoped and stamped by that tenure, not by its own wall clock (FR13)"() {
        given:
        stubLabelTransition(wireMock, 61, 'gnomish%3Aworking')
        wireMock.stubFor(post(urlEqualTo('/repos/acme/widgets/issues/61/comments'))
                .willReturn(aResponse().withStatus(201).withBody('{"id":1,"body":"whatever"}')))
        def abortedAt = Instant.parse('2026-07-19T08:00:00Z')

        when:
        newWritesHolding(4242).recordAbort(refFor(61),
                new AbortRecord('build failed', 'gnomish-factory-x7k2q1', abortedAt))

        then: 'the marker carries the tenure epoch and is keyed by it, and keeps the record\'s own time'
        def parsed = GithubMarker.parse(postedMarkerOn(61)).get()
        parsed.epoch() == new ClaimEpoch(4242)
        parsed.identity().intent() == 'abort@4242'
        parsed.at() == abortedAt
    }

    def "#kind of a live tenure carries that tenure's epoch (FR13)"() {
        given:
        stubLabelTransition(wireMock, issue, 'gnomish%3Aworking')
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issue}/comments"))
                .willReturn(aResponse().withStatus(201).withBody('{"id":1,"body":"whatever"}')))
        def writes = newWritesHolding(4242)

        when:
        switch (kind) {
                    case 'park' -> writes.park(refFor(issue), ParkReason.ESCALATION, 'need a decision')
                    case 'finish' -> writes.finish(refFor(issue), 'delivered')
                    default -> writes.recordProgress(refFor(issue))
                }

        then:
        def parsed = GithubMarker.parse(postedMarkerOn(issue)).get()
        parsed.epoch() == new ClaimEpoch(4242)
        parsed.identity().intent() == kind + '@4242'

        where:
        kind | issue
        'park' | 62
        'finish' | 63
        'progress' | 64
    }
}
