package com.github.oinsio.gnomish.adapter.tracker.github

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.delete
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.get
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo

import ch.qos.logback.classic.Level
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.app.port.tracker.TrackerUnavailableException
import com.github.oinsio.gnomish.testfixtures.logging.LogCaptureSupport
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import spock.lang.Specification

/**
 * GithubIndexRepair (github-tracker "Index-repair physics"): the labels are brought to the state
 * the recorded truth implies, with the repair marker posted before the flips. A working label with
 * no claim footprint rolls back to ready; a boundary marker after the newest claim completes its
 * own flip. A re-read that no longer matches the caller's observation writes nothing.
 *
 * FR19, FR12 of harden-task-branch-contract.
 */
class GithubIndexRepairSpec extends Specification {

    private static final GithubStateLabels LABELS =
    new GithubStateLabels('gnomish:ready', 'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered')

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
    }

    def cleanup() {
        wireMock.stop()
    }

    // FR12: the claim sequence's frozen window — working label, no claim comment — rolls back to
    //     ready, and the repair marker lands before the flip so the record survives a later failure.
    def "a claim-pending window rolls back to ready, marker first"() {
        given:
        stubIssue(7, ['gnomish:working'])
        stubComments(7, '[]')
        stubMarkerPost(7)
        stubLabelTransition(7, 'gnomish%3Aworking')

        when:
        def result = newRepair().repairIndex(refFor(7),
                TrackerFacts.of(StateLabels.workingOnly(), new ClaimFacts.None()))

        then: 'the repair marker names the observed shape and the ready label goes on'
        result instanceof RepairIndexResult.Repaired
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/7/comments'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"index_repair"')))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('claim pending'))))
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/7/labels'))
                .withRequestBody(WireMock.containing('gnomish:ready')))
        wireMock.verify(deleteRequestedFor(urlEqualTo('/repos/acme/widgets/issues/7/labels/gnomish%3Aworking')))

        and: 'and the marker really landed FIRST — both label writes come after it, in that order'
        mutationsInOrder() == [
            'POST /repos/acme/widgets/issues/7/comments',
            'POST /repos/acme/widgets/issues/7/labels',
            'DELETE /repos/acme/widgets/issues/7/labels/gnomish%3Aworking',
        ]
    }

    // FR12: a finish marker under a still-working label completes its own flip to delivered, and
    //     the finished work is never re-executed — no report is rewritten, only the labels move.
    def "a finish marker's flip is completed to delivered"() {
        given:
        stubIssue(8, ['gnomish:working'])
        stubComments(8, '[' + claimComment(500) + ',' + boundaryComment(501, 'finish') + ']')
        stubMarkerPost(8)
        stubLabelTransition(8, 'gnomish%3Aworking')

        when:
        def result = newRepair().repairIndex(refFor(8), new TrackerFacts(
                        StateLabels.workingOnly(), new ClaimFacts.Dead('gnomish-factory-dead'), BoundaryKind.FINISH))

        then:
        result instanceof RepairIndexResult.Repaired
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/8/labels'))
                .withRequestBody(WireMock.containing('gnomish:delivered')))

        and: 'the repair marker precedes the flip it completes'
        mutationsInOrder().first() == 'POST /repos/acme/widgets/issues/8/comments'
    }

    def "a #kind marker's flip is completed to #target"() {
        given:
        stubIssue(9, ['gnomish:working'])
        stubComments(9, '[' + claimComment(500) + ',' + boundaryComment(501, kind) + ']')
        stubMarkerPost(9)
        stubLabelTransition(9, 'gnomish%3Aworking')

        when:
        def result = newRepair().repairIndex(refFor(9), new TrackerFacts(
                        StateLabels.workingOnly(), new ClaimFacts.Dead('gnomish-factory-dead'), boundary))

        then:
        result instanceof RepairIndexResult.Repaired
        wireMock.verify(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/9/labels'))
                .withRequestBody(WireMock.containing(target)))

        and: 'every kind posts its marker before touching a label'
        mutationsInOrder().first() == 'POST /repos/acme/widgets/issues/9/comments'

        where:
        kind | boundary || target
        'abort' | BoundaryKind.ABORT || 'gnomish:ready'
        'park' | BoundaryKind.PARK || 'gnomish:needs-human'
        'stale_claim_removed' | BoundaryKind.STALE_CLAIM_REMOVED || 'gnomish:ready'
    }

    // The convergence property: a re-read that no longer matches the observation writes nothing and
    //     reports the current facts, so two reapers repairing the same shape converge.
    def "a re-read that no longer matches the observation writes nothing"() {
        given: 'a fresh claim comment landed since the sweep observed the frozen window'
        stubIssue(10, ['gnomish:working'])
        stubComments(10, '[' + claimComment(700) + ']')
        stubMarkerPost(10)
        stubLabelTransition(10, 'gnomish%3Aworking')

        when:
        def result = newRepair().repairIndex(refFor(10),
                TrackerFacts.of(StateLabels.workingOnly(), new ClaimFacts.None()))

        then: 'nothing is written, and the current facts are reported back'
        result instanceof RepairIndexResult.Unchanged
        (result as RepairIndexResult.Unchanged).facts().claim() instanceof ClaimFacts.Live
        wireMock.findAll(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/10/comments'))).isEmpty()
        wireMock.findAll(postRequestedFor(urlEqualTo('/repos/acme/widgets/issues/10/labels'))).isEmpty()
    }

    // FR5 of harden-logging-observability: a repair rewrites another instance's labels, so the one
    // that happens is an INFO anchor naming the shape it corrected; a converge-abort is DEBUG.
    def "FR5: a repair that acts is an INFO anchor naming the observed shape"() {
        given:
        stubIssue(11, ['gnomish:working'])
        stubComments(11, '[]')
        stubMarkerPost(11)
        stubLabelTransition(11, 'gnomish%3Aworking')
        def logs = LogCaptureSupport.attach(GithubIndexRepair, Level.DEBUG)

        when:
        newRepair().repairIndex(refFor(11), TrackerFacts.of(StateLabels.workingOnly(), new ClaimFacts.None()))

        then:
        def infos = logs.list.findAll { it.level == Level.INFO }
        infos.size() == 1
        infos[0].formattedMessage.contains('acme/widgets#11')
        infos[0].formattedMessage.contains('claim pending')

        cleanup:
        logs.detach()
    }

    def "FR5: a converge-abort is DEBUG, not an anchor and not a warning"() {
        given: 'a fresh claim comment landed since the sweep observed the frozen window'
        stubIssue(12, ['gnomish:working'])
        stubComments(12, '[' + claimComment(700) + ']')
        def logs = LogCaptureSupport.attach(GithubIndexRepair, Level.DEBUG)

        when:
        def result = newRepair().repairIndex(refFor(12),
                TrackerFacts.of(StateLabels.workingOnly(), new ClaimFacts.None()))

        then:
        result instanceof RepairIndexResult.Unchanged

        and:
        logs.list.size() == 1
        logs.list[0].level == Level.DEBUG
        logs.list[0].formattedMessage.contains('converged without acting')

        cleanup:
        logs.detach()
    }

    // FR18: a failed read of the repair sequence is a retryable tracker outage, so a later sweep
    //     tick retries it rather than the reaper treating it as a terminal fault.
    def "a non-2xx on the issue re-read is a retryable tracker outage"() {
        given:
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/11'))
                .willReturn(aResponse().withStatus(403)))

        when:
        newRepair().repairIndex(refFor(11), TrackerFacts.of(StateLabels.workingOnly()))

        then:
        def failure = thrown(GithubIndexRepairException)
        failure instanceof TrackerUnavailableException
    }

    def "a non-2xx on the comments re-read is a retryable tracker outage"() {
        given:
        stubIssue(12, ['gnomish:working'])
        wireMock.stubFor(get(urlEqualTo('/repos/acme/widgets/issues/12/comments?per_page=100'))
                .willReturn(aResponse().withStatus(500)))

        when:
        newRepair().repairIndex(refFor(12), TrackerFacts.of(StateLabels.workingOnly()))

        then:
        thrown(GithubIndexRepairException)
    }

    /**
     * Every mutating request the repair made, oldest first, as {@code "METHOD /path"}. WireMock
     * reports serve events newest first, so the reverse is chronological order.
     *
     * <p>This is what makes "marker first" an assertion rather than a comment: three independent
     * {@code verify} calls prove each request happened and say nothing about the order they
     * happened in, yet the order is the whole crash-consistency property — the repair marker is the
     * durable record of the intent, and a label flip that lands before it leaves a window where the
     * labels moved with nothing on the issue explaining why (constructive before destructive,
     * `.claude/rules/crash-consistency.md`).
     */
    private List<String> mutationsInOrder() {
        wireMock.allServeEvents.reverse()
                .findAll {
                    String.valueOf(it.request.method) in ['POST', 'DELETE']
                }
                .collect {
                    "${String.valueOf(it.request.method)} ${it.request.url}".toString()
                }
    }

    private GithubIndexRepair newRepair() {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        new GithubIndexRepair(httpClient, new GithubLabelOps(httpClient),
                new GithubMarkerWriter(new GithubCommentUpsert(httpClient), ClaimEpochSource.NONE, 'gnomish-reaper'),
                LABELS)
    }

    private static RetryConfig fastRetryConfig() {
        RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private TaskRef refFor(int issueNumber) {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', issueNumber).canonicalId())
    }

    private void stubIssue(int issueNumber, List<String> labels) {
        def labelsJson = labels.collect { "{\"name\":\"${it}\"}" }.join(',')
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}"))
                .willReturn(aResponse().withStatus(200)
                .withBody("{\"number\":${issueNumber},\"state\":\"open\",\"labels\":[${labelsJson}]}")))
    }

    private void stubComments(int issueNumber, String body) {
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody(body)))
    }

    private void stubMarkerPost(int issueNumber) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/comments"))
                .willReturn(aResponse().withStatus(201).withBody('{"id":9001,"body":"marker"}')))
    }

    private void stubLabelTransition(int issueNumber, String removedLabelEncoded) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo("/repos/acme/widgets/issues/${issueNumber}/labels/${removedLabelEncoded}"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
    }

    private static String claimComment(long id) {
        """{"id":${id},"updated_at":"2026-07-23T10:00:00Z","created_at":"2026-07-23T10:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-dead\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"}"""
    }

    private static String boundaryComment(long id, String kind) {
        """{"id":${id},"updated_at":"2026-07-23T10:30:00Z","created_at":"2026-07-23T10:30:00Z","body":"<!-- gnomish {\\"kind\\":\\"${kind}\\",\\"instance\\":\\"gnomish-factory-dead\\",\\"at\\":\\"2026-07-23T10:30:00Z\\",\\"version\\":1} -->\\n🤖 boundary"}"""
    }
}
