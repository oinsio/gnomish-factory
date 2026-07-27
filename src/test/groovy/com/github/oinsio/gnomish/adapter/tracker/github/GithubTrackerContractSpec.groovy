package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.port.tracker.contract.TrackerFetchContract
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse

/**
 * Wires the REAL production {@link GithubTracker} (composing the real {@link
 * GithubFeedQuery}, {@link GithubTaskFetcher}, {@link GithubClaimLease},
 * {@link GithubStateWrites}, {@link GithubCorrespondence}, {@link
 * GithubDecisions} — every one of them unmodified) against a WireMock server
 * into the full port contract suite (task 4.16, FR4, M1, M2): {@link
 * TrackerFetchContract} transitively runs every property from {@code
 * TrackerContract}, {@code TrackerMarkerContract}, and itself against this
 * one adapter, with zero adapter-specific exemptions.
 *
 * <p>The abstract suite's fixture {@link TaskRef} strings (e.g. {@code
 * fixture:ready-a}) do not match GitHub's canonical {@code
 * github:owner/repo#N} id format that every {@code Github*} production class
 * requires as its very first parsing step ({@link GithubTaskId#parse}).
 * Rather than weaken that production parsing for a testing convenience, this
 * spec wraps the real adapter in a test-only translation layer, {@link
 * GithubTrackerFixtureAdapter}: it assigns each fixture ref a synthetic
 * canonical GitHub ref (issue numbers 1, 2, 3, ... in first-seen order),
 * seeds a {@link FixtureIssueRegistry}-backed WireMock server (via {@link
 * FixtureGithubTransformer}, a dynamic {@code
 * ResponseDefinitionTransformerV2} whose "get issue"/"list comments"/"list
 * issues" responses reflect every prior label/comment mutation any caller
 * has made) with the requested logical state, and translates {@link
 * TaskRef} values back to the original fixture ref in every result — so the
 * REAL, unmodified GitHub adapter code is what actually runs the HTTP calls
 * this suite verifies, and only the fixture-ref mismatch is absorbed in
 * test-only glue that never ships.
 *
 * <p>The dynamic transformer is also what makes the 12-way concurrent
 * {@code claim} race (5 repetitions x 12 callers each, on distinct issues)
 * correct: every racing caller's claim-comment POST and verify-read GET goes
 * through the SAME {@link FixtureIssueRegistry}, whose comment ids are
 * minted from one shared, atomically-incrementing sequence — exactly
 * mirroring GitHub's own global, per-repository comment-id total order that
 * the real {@link GithubClaimLease} relies on to decide "earliest id wins".
 *
 * <p>Implements FR4, NFR-R1 of add-tracker-port.
 */
class GithubTrackerContractSpec extends TrackerFetchContract {

    private static final String OWNER = 'acme'
    private static final String REPO = 'widgets'
    private static final String INSTANCE_ID = 'gnomish-factory-contract'

    private WireMockServer wireMock
    private GithubTrackerFixtureAdapter fixtureAdapter

    def setup() {
        def registry = new FixtureIssueRegistry()
        wireMock = new WireMockServer(WireMockConfiguration.options()
                .dynamicPort()
                .extensions(new FixtureGithubTransformer(registry)))
        wireMock.start()

        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'contract-test-token', fastRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        def labelOps = new GithubLabelOps(httpClient)

        def realTracker = new GithubTracker(
                new GithubFeedQuery(cache, OWNER, REPO, FixtureSeeder.READY_LABEL),
                new GithubTaskFetcher(cache, FixtureSeeder.WORKING_LABEL, FixtureSeeder.NEEDS_HUMAN_LABEL),
                new GithubClaimLease(httpClient, labelOps, FixtureSeeder.READY_LABEL, FixtureSeeder.WORKING_LABEL),
                new GithubStateWrites(httpClient, labelOps, INSTANCE_ID,
                FixtureSeeder.WORKING_LABEL, FixtureSeeder.NEEDS_HUMAN_LABEL,
                FixtureSeeder.DELIVERED_LABEL, FixtureSeeder.READY_LABEL),
                new GithubCorrespondence(httpClient, INSTANCE_ID),
                new GithubDecisions(httpClient, INSTANCE_ID))

        fixtureAdapter = new GithubTrackerFixtureAdapter(
                realTracker, registry, wireMock.baseUrl(), OWNER, REPO, INSTANCE_ID)
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

    @Override
    protected Optional<Tracker> arrange() {
        Optional.of(fixtureAdapter)
    }

    @Override
    protected void seedTask(Tracker adapter, TaskRef ref, TaskSnapshot snapshot, TrackerTaskState state, AbortFacts abortFacts) {
        fixtureAdapter.seedTask(ref, snapshot, state, abortFacts)
    }

    @Override
    protected void seedReply(Tracker adapter, TaskRef ref, HumanReply reply) {
        fixtureAdapter.seedReply(ref, reply)
    }
}
