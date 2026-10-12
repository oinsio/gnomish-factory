package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.adapter.github.GithubConditionalRequestCache
import com.github.oinsio.gnomish.adapter.github.GithubHttpClient
import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind
import com.github.oinsio.gnomish.app.port.tracker.ClaimEpochSource
import com.github.oinsio.gnomish.app.port.tracker.StateLabels
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerFacts
import com.github.oinsio.gnomish.domain.branch.ClaimEpoch
import com.github.oinsio.gnomish.domain.engine.fake.VirtualClock
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.http.RequestMethod
import io.github.resilience4j.core.IntervalFunction
import io.github.resilience4j.retry.RetryConfig
import java.net.http.HttpResponse
import java.time.Instant

/**
 * One seeded task on a stateful WireMock GitHub, driven by the real production {@link
 * GithubTracker}, with {@link KillAfterWrites} armed in front of it — the world {@code
 * GithubKillWindowSpec} freezes a kill window in.
 *
 * <p>Separate from the spec so the spec is only the transition table and the window loop: this
 * class owns the fixture's assembly, the canonical-ref discovery, and the one reading that matters
 * afterwards — what the sweep's own listings report.
 *
 * <p>The scenario specs of the github-tracker capability reuse the same world without a kill, to
 * observe what one operation leaves on a real-shaped issue: {@link #issue} is the issue itself, {@link
 * #writeLog} the mutating requests the adapter sent, and {@link #holdTenure} the tenure the adapter's
 * marker writer stamps — the role the claim-epoch book plays in production.
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
    private FixtureIssue issue
    private volatile ClaimEpoch tenure

    /**
     * Starts a fresh server, seeds the issue through {@code seed}, and discovers its canonical ref;
     * {@code rules} are the designator rules the fetcher extracts with.
     */
    void open(Closure seed, GithubDesignatorRules rules = GithubDesignatorRules.none()) {
        close()
        tenure = null
        def registry = new FixtureIssueRegistry()
        faults = new KillAfterWrites(new FixtureGithubTransformer(registry))
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort().extensions(faults))
        wireMock.start()
        issue = registry.issueFor(ISSUE)
        issue.title('a task')
        seed.call(new FixtureSeeder(registry, HOLDER), issue)
        tracker = newTracker(rules)
        ref = discoverRef()
    }

    Tracker tracker() {
        tracker
    }

    TaskRef ref() {
        ref
    }

    /** The seeded issue as the fixture GitHub holds it: its labels and its comment thread. */
    FixtureIssue issue() {
        issue
    }

    /** The tenure the adapter's marker writer stamps from now on; {@code null} for none. */
    void holdTenure(ClaimEpoch epoch) {
        tenure = epoch
    }

    /** Forgets every request seen so far, so {@link #writeLog} starts from here. */
    void resetLog() {
        wireMock.resetRequests()
    }

    /** Every mutating request since the last {@link #resetLog}, as {@code METHOD url}, in order. */
    List<String> writeLog() {
        wireMock.allServeEvents.reverse()
                .collect { it.request }
                .findAll { it.method != RequestMethod.GET }
                .collect { "${it.method} ${it.url}".toString() }
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

    private Tracker newTracker(GithubDesignatorRules rules) {
        def labels = new GithubStateLabels(FixtureSeeder.READY_LABEL, FixtureSeeder.WORKING_LABEL,
                FixtureSeeder.NEEDS_HUMAN_LABEL, FixtureSeeder.DELIVERED_LABEL)
        def httpClient = new GithubHttpClient(wireMock.baseUrl(), 'kill-window-token', fastRetryConfig())
        def cache = new GithubConditionalRequestCache(httpClient)
        def labelOps = new GithubLabelOps(httpClient)
        // One clock for every collaborator, set after every marker the seeder writes (year 2000): the
        // folds order markers by their own stamp, so the adapter's writes must come after the seed's.
        def clock = new VirtualClock(Instant.parse('2026-01-01T00:00:00Z'))
        def epochs = { String taskId ->
            Optional.ofNullable(tenure)
        } as ClaimEpochSource
        def markers = new GithubMarkerWriter(new GithubCommentUpsert(httpClient), epochs, KILLER, clock)
        new GithubTracker(
                new GithubFeedQuery(cache, OWNER, REPO, FixtureSeeder.READY_LABEL),
                new GithubTaskFetcher(cache, FixtureSeeder.WORKING_LABEL, FixtureSeeder.NEEDS_HUMAN_LABEL,
                FixtureSeeder.DELIVERED_LABEL, rules),
                new GithubClaimLease(httpClient, labelOps, FixtureSeeder.READY_LABEL, FixtureSeeder.WORKING_LABEL, clock),
                new GithubStateWrites(httpClient, labelOps, markers, FixtureSeeder.WORKING_LABEL,
                FixtureSeeder.NEEDS_HUMAN_LABEL, FixtureSeeder.DELIVERED_LABEL, FixtureSeeder.READY_LABEL),
                new GithubCorrespondence(markers),
                new GithubDecisions(httpClient, markers, clock),
                new GithubHeartbeat(httpClient, KILLER, clock),
                new GithubOpenQuery(cache, OWNER, REPO, labels),
                new GithubStaleClaimRemoval(httpClient, labelOps, markers, FixtureSeeder.WORKING_LABEL,
                FixtureSeeder.READY_LABEL, clock),
                new GithubIndexRepair(httpClient, labelOps, markers, labels, clock))
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
