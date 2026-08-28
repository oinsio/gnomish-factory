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

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.ClaimFacts
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult
import com.github.oinsio.gnomish.app.port.tracker.ParkReason
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult
import com.github.oinsio.gnomish.app.port.tracker.RepairIndexResult
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant
import spock.lang.Specification

/**
 * {@link GithubTracker}'s pure delegation wiring (task 4.16): every method forwards to exactly
 * the collaborator that implements it, unmodified. {@code GithubTrackerContractSpec} exercises
 * the read-focused contract properties (feed, claim, markers, fetchTask) against a real WireMock
 * server, but never calls {@code park}/{@code finish}/{@code postNote}/{@code recordAbort} (task
 * 0.2: the contract suite has no direct human-decision-wait dialog to drive these write-only
 * transitions) — this spec proves those delegate correctly, using real collaborators over
 * WireMock (the collaborator classes are {@code final}, so mocking them is not an option; their
 * own behavior is already covered in depth by {@code GithubStateWritesSpec}/{@code
 * GithubCorrespondenceSpec}, whose exact stubbing pattern this spec reuses).
 *
 * <p>Implements FR1, FR4, NFR-R1 of add-tracker-port.
 */
class GithubTrackerSpec extends Specification {

    private static final int ISSUE_NUMBER = 50

    WireMockServer wireMock

    def setup() {
        wireMock = new WireMockServer(0)
        wireMock.start()
        // The find half of the FR11 find-then-upsert primitive: every factory comment write reads
        // the thread first. Specs that need a populated thread add their own, more recent stub.
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .get(com.github.tomakehurst.wiremock.client.WireMock.urlMatching('.*/comments\\?per_page=100'))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
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

    private void stubLabelTransition(String removedLabelEncoded) {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/labels"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        wireMock.stubFor(delete(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/labels/${removedLabelEncoded}"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
    }

    private void stubComment() {
        wireMock.stubFor(post(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .willReturn(aResponse().withStatus(201).withBody('{"id":1,"body":"whatever"}')))
    }

    private static final GithubStateLabels LABELS =
    new GithubStateLabels('gnomish:ready', 'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered')

    private GithubTracker newTracker() {
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'tok', fastRetryConfig())
        def labelOps = new GithubLabelOps(httpClient)
        def cache = new GithubConditionalRequestCache(httpClient)
        new GithubTracker(
                new GithubFeedQuery(cache, 'acme', 'widgets', 'gnomish:ready'),
                new GithubTaskFetcher(cache, 'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered'),
                new GithubClaimLease(httpClient, labelOps, 'gnomish:ready', 'gnomish:working'),
                new GithubStateWrites(httpClient, labelOps, markerWriter(httpClient, 'gnomish-factory-x7k2q1'),
                'gnomish:working', 'gnomish:needs-human', 'gnomish:delivered', 'gnomish:ready'),
                new GithubCorrespondence(markerWriter(httpClient, 'gnomish-factory-x7k2q1')),
                new GithubDecisions(httpClient, markerWriter(httpClient, 'gnomish-factory-x7k2q1')),
                new GithubHeartbeat(httpClient, 'gnomish-factory-x7k2q1'),
                new GithubOpenQuery(cache, 'acme', 'widgets', LABELS),
                new GithubStaleClaimRemoval(httpClient, labelOps, markerWriter(httpClient, 'gnomish-factory-x7k2q1'),
                'gnomish:working', 'gnomish:ready'),
                new GithubIndexRepair(httpClient, labelOps, markerWriter(httpClient, 'gnomish-factory-x7k2q1'), LABELS))
    }

    private TaskRef ref() {
        new TaskRef(GithubTaskId.build(wireMock.baseUrl(), 'acme', 'widgets', ISSUE_NUMBER).canonicalId())
    }

    def "park delegates to GithubStateWrites, posting a structural park marker"() {
        given:
        stubLabelTransition('gnomish%3Aworking')
        stubComment()

        when:
        newTracker().park(ref(), ParkReason.CHECKPOINT, 'paused')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"park"'))))
    }

    def "finish delegates to GithubStateWrites, posting a structural finish marker"() {
        given:
        stubLabelTransition('gnomish%3Aworking')
        stubComment()

        when:
        newTracker().finish(ref(), 'delivered')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"finish"'))))
    }

    def "postNote delegates to GithubCorrespondence, posting a structural note marker"() {
        given:
        stubComment()

        when:
        newTracker().postNote(ref(), 'still working on it')

        then:
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"note"'))))
    }

    def "release delegates to GithubCorrespondence and makes no HTTP call at all"() {
        when:
        newTracker().release(ref())

        then:
        wireMock.allServeEvents.isEmpty()
    }

    def "recordAbort delegates to GithubStateWrites, posting a structural abort marker"() {
        given:
        stubLabelTransition('gnomish%3Aworking')
        stubComment()
        def record = new AbortRecord('boom', 'gnomish-factory-x7k2q1', Instant.parse('2026-07-20T10:00:00Z'))

        when:
        newTracker().recordAbort(ref(), record)

        then:
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"abort"'))))
    }

    def "heartbeat delegates to GithubHeartbeat, PATCHing the resolved claim comment"() {
        given:
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":501,"updated_at":"2026-07-23T10:00:00Z","created_at":"2026-07-23T10:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-x7k2q1\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"}
                        ]
                        ''')))
        wireMock.stubFor(patch(urlPathEqualTo('/repos/acme/widgets/issues/comments/501'))
                .willReturn(aResponse().withStatus(200)
                .withBody('{"id":501,"updated_at":"2026-07-23T10:05:00Z","body":"refreshed"}')))

        when:
        def result = newTracker().heartbeat(ref(), 'progress')

        then:
        result instanceof HeartbeatResult.Beaten
        wireMock.verify(patchRequestedFor(urlPathEqualTo('/repos/acme/widgets/issues/comments/501'))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"claim"'))))
    }

    def "removeStaleClaim delegates to GithubStaleClaimRemoval, posting the stale-claim-removed marker"() {
        given: 'the observed claim (id 501) still matches; the removal posts the boundary marker, deletes it, flips the label'
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody('''
                        [
                          {"id":501,"updated_at":"2026-07-23T10:00:00Z","created_at":"2026-07-23T10:00:00Z","body":"<!-- gnomish {\\"kind\\":\\"claim\\",\\"instance\\":\\"gnomish-factory-dead\\",\\"at\\":\\"2026-07-23T10:00:00Z\\",\\"version\\":1} -->\\n🤖 claimed"}
                        ]
                        ''')))
        stubComment()
        wireMock.stubFor(delete(urlEqualTo('/repos/acme/widgets/issues/comments/501'))
                .willReturn(aResponse().withStatus(204)))
        stubLabelTransition('gnomish%3Aworking')

        when:
        def result = newTracker().removeStaleClaim(ref(), new ClaimFacts.Live('gnomish-factory-dead',
                new ClaimVersion('501', Instant.parse('2026-07-23T10:00:00Z'), new ClaimEpoch(501))))

        then:
        result instanceof RemoveStaleClaimResult.Removed
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"stale_claim_removed"'))))
    }

    // FR19 of harden-task-branch-contract: repairIndex is delegated to the index-repair
    //     collaborator, and its result is the one this composition returns — never a substituted one.
    def "repairIndex delegates to GithubIndexRepair, posting the repair marker and flipping the label"() {
        given: 'a working-labeled issue whose thread carries no claim comment'
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}"))
                .willReturn(aResponse().withStatus(200).withBody(
                        '{"number":' + ISSUE_NUMBER + ',"state":"open","labels":[{"name":"gnomish:working"}]}')))
        wireMock.stubFor(get(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments?per_page=100"))
                .willReturn(aResponse().withStatus(200).withBody('[]')))
        stubComment()
        stubLabelTransition('gnomish%3Aworking')

        when:
        def result = newTracker().repairIndex(ref(),
                TrackerFacts.of(StateLabels.workingOnly(), new ClaimFacts.None()))

        then:
        result instanceof RepairIndexResult.Repaired
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/comments"))
                .withRequestBody(WireMock.matchingJsonPath('$.body', WireMock.containing('"kind":"index_repair"'))))
        wireMock.verify(postRequestedFor(urlEqualTo("/repos/acme/widgets/issues/${ISSUE_NUMBER}/labels"))
                .withRequestBody(WireMock.containing('gnomish:ready')))
    }

    private static GithubMarkerWriter markerWriter(httpClient, String instanceId) {
        new GithubMarkerWriter(new GithubCommentUpsert(httpClient), ClaimEpochSource.NONE, instanceId)
    }
}
