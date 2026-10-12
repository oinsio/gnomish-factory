package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.InstantSource

/**
 * The REAL production {@link GithubTracker} — the real {@link GithubFeedQuery}, {@link
 * GithubTaskFetcher}, {@link GithubClaimLease}, {@link GithubStateWrites}, {@link
 * GithubCorrespondence}, {@link GithubDecisions}, {@link GithubHeartbeat}, {@link GithubOpenQuery},
 * {@link GithubStaleClaimRemoval} and {@link GithubIndexRepair}, every one unmodified — wired
 * against a WireMock server whose answers reflect one shared {@link FixtureIssueRegistry}, behind
 * the {@link GithubTrackerFixtureAdapter} that translates the contract suite's fixture refs. Owned
 * here, apart from {@code GithubTrackerContractSpec}, so the spec binds the contract's seams and
 * this class owns the wiring and the out-of-band edits a human makes in the tracker UI.
 *
 * <p>The dynamic transformer is what makes the 12-way concurrent {@code claim} race correct: every
 * racing caller's claim-comment POST and verify-read GET goes through the SAME registry, whose
 * comment ids are minted from one shared, atomically-incrementing sequence — mirroring GitHub's
 * own per-repository comment-id total order that {@link GithubClaimLease} relies on.
 *
 * <p>The lease suite always models the adapter under test AS the claim holder {@code instance-a}:
 * a beat is performed BY the holder, and GitHub's {@code heartbeat} rewrites the claim comment with
 * the BEATING instance's id, which {@code fetchTask} reads back as the holder. So {@link
 * GithubHeartbeat} is wired with {@link #HOLDER_INSTANCE_ID}; the other collaborators keep the
 * neutral {@link #INSTANCE_ID}, whose markers no property asserts by author.
 *
 * <p>Implements FR4, NFR-R1 of add-tracker-port; FR1, FR4, FR5, FR8, NFR-R2, M1 of
 * add-claim-heartbeat.
 */
class GithubContractTracker {

    static final String OWNER = 'acme'
    static final String REPO = 'widgets'
    static final String INSTANCE_ID = 'gnomish-factory-contract'
    static final String HOLDER_INSTANCE_ID = 'instance-a'
    /**
     * The configured {@code tracker.github.designators.base} rule (FR3 of add-base-ref-resolution).
     * The optional {@code -<n>} suffix lets one issue present the same candidate value twice —
     * GitHub refuses a duplicate label name — so the "equal duplicates collapse" property has a
     * fixture a real issue could hold.
     */
    static final String DESIGNATOR_RULE = 'base(?:-[0-9]+)?:(.+)'

    /**
     * The tracker's "now": virtual time that moves one second per read, so successive writes carry
     * distinct stamps the way a live tracker's do, without real time (FR18, FR20 of
     * supervise-daemon-loops-and-embed-dashboard).
     */
    private static final VirtualClock CLOCK = new VirtualClock(Instant.parse('2026-07-20T12:00:00Z'))
    private static final InstantSource TICKING = {
        -> CLOCK.advance(Duration.ofSeconds(1)); CLOCK.instant()
    } as InstantSource

    final WireMockServer wireMock
    final GithubTrackerFixtureAdapter adapter
    private final FixtureIssueRegistry registry = new FixtureIssueRegistry()

    GithubContractTracker() {
        def labels = new GithubStateLabels(FixtureSeeder.READY_LABEL, FixtureSeeder.WORKING_LABEL,
                FixtureSeeder.NEEDS_HUMAN_LABEL, FixtureSeeder.DELIVERED_LABEL)
        wireMock = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(new FixtureGithubTransformer(registry)))
        wireMock.start()
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'contract-test-token', fastRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        def labelOps = new GithubLabelOps(httpClient)
        def realTracker = new GithubTracker(
                new GithubFeedQuery(cache, OWNER, REPO, FixtureSeeder.READY_LABEL),
                new GithubTaskFetcher(cache, FixtureSeeder.WORKING_LABEL, FixtureSeeder.NEEDS_HUMAN_LABEL,
                FixtureSeeder.DELIVERED_LABEL, GithubDesignatorRules.from([designators: [base: DESIGNATOR_RULE]])),
                new GithubClaimLease(httpClient, labelOps, FixtureSeeder.READY_LABEL, FixtureSeeder.WORKING_LABEL, TICKING),
                new GithubStateWrites(httpClient, labelOps, markerWriter(httpClient),
                FixtureSeeder.WORKING_LABEL, FixtureSeeder.NEEDS_HUMAN_LABEL,
                FixtureSeeder.DELIVERED_LABEL, FixtureSeeder.READY_LABEL),
                new GithubCorrespondence(markerWriter(httpClient)),
                new GithubDecisions(httpClient, markerWriter(httpClient), TICKING),
                new GithubHeartbeat(httpClient, HOLDER_INSTANCE_ID, TICKING),
                new GithubOpenQuery(cache, OWNER, REPO, labels),
                new GithubStaleClaimRemoval(httpClient, labelOps, markerWriter(httpClient),
                FixtureSeeder.WORKING_LABEL, FixtureSeeder.READY_LABEL, TICKING),
                new GithubIndexRepair(httpClient, labelOps, markerWriter(httpClient), labels, TICKING))
        adapter = new GithubTrackerFixtureAdapter(realTracker, registry, wireMock.baseUrl(), OWNER, REPO, INSTANCE_ID)
    }

    void stop() {
        wireMock.stop()
    }

    /**
     * Puts the working label back on {@code ref}'s issue and takes every other state label off,
     * touching no comment — the out-of-band edit that leaves a boundary marker under a working label.
     */
    void rewindIndexToWorking(TaskRef ref) {
        def issue = registry.issueFor(GithubTaskId.parse(adapter.canonicalRefFor(ref).id()).issueNumber())
        [
            FixtureSeeder.READY_LABEL,
            FixtureSeeder.NEEDS_HUMAN_LABEL,
            FixtureSeeder.DELIVERED_LABEL
        ].each { issue.removeLabel(it) }
        issue.addLabel(FixtureSeeder.WORKING_LABEL)
    }

    private static RetryConfig fastRetryConfig() {
        RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                // Matches everything rather than naming the adapter's package-private
                // GithubHttpUncheckedIOException (illegal cross-package access from this package,
                // see FeedAutomatonOutageIntegrationSpec) -- harmless here since the only
                // exception this predicate ever actually sees is a real transport failure.
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }

    private static GithubMarkerWriter markerWriter(GithubHttpClient httpClient) {
        new GithubMarkerWriter(new GithubCommentUpsert(httpClient), ClaimEpochSource.NONE, INSTANCE_ID, TICKING)
    }
}
