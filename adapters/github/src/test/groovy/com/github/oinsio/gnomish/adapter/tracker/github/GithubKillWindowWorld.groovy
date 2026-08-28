package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse

/**
 * One seeded task on a stateful WireMock GitHub, driven by the real production {@link
 * GithubTracker}, with {@link KillAfterWrites} armed in front of it — the world {@code
 * GithubKillWindowSpec} freezes a kill window in.
 *
 * <p>Separate from the spec so the spec is only the transition table and the window loop: this
 * class owns the fixture's assembly, the canonical-ref discovery, and the one reading that matters
 * afterwards — what the sweep's own listings report.
 *
 * <p>Test-only: never shipped.
 */
class GithubKillWindowWorld {

    static final String OWNER = 'acme'
    static final String REPO = 'widgets'
    static final int ISSUE = 7
    static final String HOLDER = 'gnomish-factory-holder'
    static final String KILLER = 'gnomish-factory-killer'

    private WireMockServer wireMock
    private KillAfterWrites faults
    private Tracker tracker
    private TaskRef ref

    /** Starts a fresh server, seeds the issue through {@code seed}, and discovers its canonical ref. */
    void open(Closure seed) {
        close()
        def registry = new FixtureIssueRegistry()
        faults = new KillAfterWrites(new FixtureGithubTransformer(registry))
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(faults))
        wireMock.start()
        def issue = registry.issueFor(ISSUE)
        issue.title('a task')
        seed.call(new FixtureSeeder(registry, HOLDER), issue)
        tracker = newTracker()
        ref = discoverRef()
    }

    void close() {
        wireMock?.stop()
    }

    /** Lets {@code allowed} mutating requests through, then fails the connection on every later one. */
    void killAfter(int allowed) {
        faults.killAfter(allowed)
    }

    /** Lets every request through — the counting pass, and the fact reads after a kill. */
    void noKill() {
        faults.noKill()
    }

    int writesSeen() {
        faults.writesSeen()
    }

    /** Runs the sequence; a killed one surfaces as a tracker failure, which IS the scenario. */
    void attempt(Closure act) {
        try {
            act.call(tracker, ref)
        } catch (RuntimeException ignored) {
            // The kill is what this world exists to produce.
        }
    }

    /**
     * The facts the sweep's own universe reports for the task, or null when it left both listings.
     *
     * <p>The ready-feed derivation mirrors {@code TrackerObservation.readyFacts}, which owns it in
     * production — reachable only from the composition layer, so the sweep's own reading is
     * reproduced here in the two lines it takes rather than depended on.
     */
    TrackerFacts sweepFacts() {
        def open = tracker.listOpen().find { it.ref() == ref }
        if (open != null) {
            return open.facts()
        }
        def entry = tracker.listReady(50).find { it.ref() == ref }
        entry == null ? null : new TrackerFacts(StateLabels.readyOnly(), entry.claim(),
                entry.finished() ? BoundaryKind.FINISH : entry.returned() ? BoundaryKind.PARK : null)
    }

    /** The canonical ref the adapter itself reports for the seeded issue — host-qualified. */
    private TaskRef discoverRef() {
        def suffix = "#${ISSUE}"
        tracker.listOpen().find { it.ref().id().endsWith(suffix) }?.ref()
        ?: tracker.listReady(50).find {
            it.ref().id().endsWith(suffix)
        }?.ref()
    }

    private Tracker newTracker() {
        def labels = new GithubStateLabels(FixtureSeeder.READY_LABEL, FixtureSeeder.WORKING_LABEL,
                FixtureSeeder.NEEDS_HUMAN_LABEL, FixtureSeeder.DELIVERED_LABEL)
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'kill-window-token', fastRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        def labelOps = new GithubLabelOps(httpClient)
        def markers = new GithubMarkerWriter(new GithubCommentUpsert(httpClient), ClaimEpochSource.NONE, KILLER)
        new GithubTracker(
                new GithubFeedQuery(cache, OWNER, REPO, FixtureSeeder.READY_LABEL),
                new GithubTaskFetcher(cache, FixtureSeeder.WORKING_LABEL, FixtureSeeder.NEEDS_HUMAN_LABEL,
                FixtureSeeder.DELIVERED_LABEL),
                new GithubClaimLease(httpClient, labelOps, FixtureSeeder.READY_LABEL, FixtureSeeder.WORKING_LABEL),
                new GithubStateWrites(httpClient, labelOps, markers, FixtureSeeder.WORKING_LABEL,
                FixtureSeeder.NEEDS_HUMAN_LABEL, FixtureSeeder.DELIVERED_LABEL, FixtureSeeder.READY_LABEL),
                new GithubCorrespondence(markers),
                new GithubDecisions(httpClient, markers),
                new GithubHeartbeat(httpClient, KILLER),
                new GithubOpenQuery(cache, OWNER, REPO, labels),
                new GithubStaleClaimRemoval(httpClient, labelOps, markers, FixtureSeeder.WORKING_LABEL,
                FixtureSeeder.READY_LABEL),
                new GithubIndexRepair(httpClient, labelOps, markers, labels))
    }

    private static RetryConfig fastRetryConfig() {
        RetryConfig.custom()
                .maxAttempts(2)
                .intervalFunction(IntervalFunction.of(10))
                .retryOnException({ true })
                .retryOnResult({ HttpResponse<?> r -> r.statusCode() >= 500 })
                .build()
    }
}
