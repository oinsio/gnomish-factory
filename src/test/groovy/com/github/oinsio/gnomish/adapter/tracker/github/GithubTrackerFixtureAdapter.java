package com.github.oinsio.gnomish.adapter.tracker.github;

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord;
import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.ClaimVersion;
import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.HumanReply;
import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import com.github.oinsio.gnomish.app.port.tracker.ParkReason;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.RemoveStaleClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only translation layer bridging the abstract {@code TrackerContract}
 * suite's arbitrary fixture {@link TaskRef} strings (e.g. {@code
 * fixture:ready-a}, freely chosen inside the suite itself, not by this
 * class — see task 4.16) to the GitHub adapter's canonical id format
 * ({@code github:owner/repo#N}, enforced by {@link GithubTaskId#parse}).
 * Wraps a REAL {@link Tracker} (production {@code GithubTracker}, pointed
 * at a WireMock server) and:
 *
 * <ul>
 *   <li>assigns each never-seen fixture ref a synthetic canonical GitHub ref
 *       (issue numbers 1, 2, 3, ... in first-seen order) and remembers the
 *       mapping both ways;
 *   <li>seeds the {@link FixtureIssueRegistry} backing {@link
 *       FixtureGithubTransformer} via {@link FixtureSeeder}, so the label/
 *       comment state matches whatever {@link TrackerTaskState}/{@link
 *       AbortFacts}/{@link HumanReply} the contract suite requested;
 *   <li>translates every {@link TaskRef} the wrapped adapter returns back to
 *       the ORIGINAL fixture ref before handing results to the contract
 *       suite, so {@code result.ref() == ref} assertions against the
 *       suite's own fixture object hold.
 * </ul>
 *
 * <p>This class never ships: it lives entirely under {@code src/test} and
 * exists solely to let the real, unmodified production {@code
 * GithubTracker}/{@code GithubFeedQuery}/{@code GithubTaskFetcher}/etc. run
 * against WireMock while satisfying a contract suite written against an
 * adapter-agnostic {@link TaskRef} shape (task 4.16 design rationale).
 *
 * <p>{@code ClaimResult.Held.otherInstance()} is a plain instance-id string,
 * never a {@link TaskRef} — no translation applies to it. {@link HumanReply}
 * carries no ref field either.
 */
final class GithubTrackerFixtureAdapter implements Tracker {

    private final Tracker delegate;
    private final FixtureIssueRegistry registry;
    private final FixtureSeeder seeder;
    private final String apiUrl;
    private final String owner;
    private final String repo;
    private final Map<TaskRef, TaskRef> fixtureToCanonical = new ConcurrentHashMap<>();
    private final Map<TaskRef, TaskRef> canonicalToFixture = new ConcurrentHashMap<>();
    private final AtomicInteger nextIssueNumber = new AtomicInteger(1);

    GithubTrackerFixtureAdapter(
            Tracker realTracker, FixtureIssueRegistry registry, String apiUrl, String owner, String repo, String instanceId) {
        this.delegate = realTracker;
        this.registry = registry;
        this.seeder = new FixtureSeeder(registry, instanceId);
        this.apiUrl = apiUrl;
        this.owner = owner;
        this.repo = repo;
    }

    /** Resolves (assigning if new) the synthetic canonical ref for a fixture ref. */
    TaskRef canonicalRefFor(TaskRef fixtureRef) {
        return fixtureToCanonical.computeIfAbsent(fixtureRef, ref -> {
            int issueNumber = nextIssueNumber.getAndIncrement();
            TaskRef canonical = new TaskRef(
                    GithubTaskId.build(apiUrl, owner, repo, issueNumber).canonicalId());
            canonicalToFixture.put(canonical, ref);
            return canonical;
        });
    }

    private TaskRef toFixture(TaskRef canonicalRef) {
        TaskRef fixture = canonicalToFixture.get(canonicalRef);
        return fixture == null ? canonicalRef : fixture;
    }

    private int issueNumberOf(TaskRef canonicalRef) {
        return GithubTaskId.parse(canonicalRef.id()).issueNumber();
    }

    /** Resolves (assigning if new) the fixture issue backing {@code ref}'s canonical id. */
    private FixtureIssue issueFor(TaskRef ref) {
        return registry.issueFor(issueNumberOf(canonicalRefFor(ref)));
    }

    // --- Tracker delegation with ref translation ---

    @Override
    public List<ReadyTask> listReady(int limit) {
        return delegate.listReady(limit).stream()
                .map(rt -> new ReadyTask(toFixture(rt.ref()), rt.abortFacts(), rt.returned()))
                .toList();
    }

    @Override
    public TrackerTask fetchTask(TaskRef ref) {
        TrackerTask result = delegate.fetchTask(canonicalRefFor(ref));
        // The snapshot's own 'id' field must also translate back to the fixture ref: the
        // TrackerFetchContract suite asserts result.snapshot().id() == ref.id() against its
        // OWN fixture ref, and the real GithubTaskFetcher fills TaskSnapshot.id() from the
        // canonical ref it was called with (see GithubTaskFetcher.fetchTask), not the
        // fixture ref this wrapper hides underneath it.
        var snapshot = new TaskSnapshot(ref.id(), result.snapshot().title(), result.snapshot().body());
        return new TrackerTask(ref, snapshot, result.state(), result.abortFacts());
    }

    @Override
    public List<HumanReply> collectDecisions(TaskRef ref) {
        return delegate.collectDecisions(canonicalRefFor(ref));
    }

    @Override
    public ClaimResult claim(TaskRef ref, String callerInstanceId) {
        return delegate.claim(canonicalRefFor(ref), callerInstanceId);
    }

    @Override
    public void release(TaskRef ref) {
        delegate.release(canonicalRefFor(ref));
    }

    @Override
    public void park(TaskRef ref, ParkReason reason, String report) {
        delegate.park(canonicalRefFor(ref), reason, report);
    }

    @Override
    public void finish(TaskRef ref, String summary) {
        delegate.finish(canonicalRefFor(ref), summary);
    }

    @Override
    public void recordAbort(TaskRef ref, AbortRecord record) {
        delegate.recordAbort(canonicalRefFor(ref), record);
    }

    @Override
    public void acknowledgeDecision(TaskRef ref, String decisionText) {
        delegate.acknowledgeDecision(canonicalRefFor(ref), decisionText);
    }

    @Override
    public void postNote(TaskRef ref, String text) {
        delegate.postNote(canonicalRefFor(ref), text);
    }

    @Override
    public void recordProgress(TaskRef ref) {
        delegate.recordProgress(canonicalRefFor(ref));
    }

    @Override
    public List<OpenTask> listOpen() {
        // Like listReady, each returned entry's canonical ref translates back to the fixture ref the
        // contract suite seeded; the opaque ClaimVersion and the state carry through unchanged.
        return delegate.listOpen().stream()
                .map(open -> new OpenTask(toFixture(open.ref()), open.state(), open.claimVersion()))
                .toList();
    }

    @Override
    public HeartbeatResult heartbeat(TaskRef ref, String progressPayload) {
        // HeartbeatResult (Beaten/ClaimGone) carries no TaskRef, so only the input ref is translated.
        return delegate.heartbeat(canonicalRefFor(ref), progressPayload);
    }

    @Override
    public RemoveStaleClaimResult removeStaleClaim(TaskRef ref, ClaimVersion observedVersion) {
        // RemoveStaleClaimResult (Removed/Mismatch) carries no TaskRef, so only the input ref is translated.
        return delegate.removeStaleClaim(canonicalRefFor(ref), observedVersion);
    }

    // --- Fixture seeding, delegated to FixtureSeeder for the wire-shape details ---

    /**
     * Seeds a fixture task carrying {@code snapshot} at {@code state} with {@code
     * abortFacts}, per {@code TrackerContract.seedTask}. The snapshot's title and body
     * are written onto the live fixture issue so the real {@code GithubTaskFetcher}
     * reads them back verbatim through {@code fetchTask} (FR11).
     */
    void seedTask(TaskRef ref, TaskSnapshot snapshot, TrackerTaskState state, AbortFacts abortFacts) {
        FixtureIssue issue = issueFor(ref);
        issue.title(snapshot.title());
        issue.body(snapshot.body());
        seeder.seedTask(issue, state, abortFacts);
    }

    /** Seeds a pending human reply comment, per {@code TrackerContract.seedReply}. */
    void seedReply(TaskRef ref, HumanReply reply) {
        seeder.seedReply(issueFor(ref), reply);
    }

    /**
     * Seeds a {@code Working(holder)} fixture issue WITH a live claim comment, per {@code
     * TrackerLeaseContract.seedWorkingWithClaim}, so {@code listOpen}/{@code heartbeat}/{@code
     * removeStaleClaim} resolve a non-null claim version through the real adapter.
     */
    void seedWorkingWithClaim(TaskRef ref, String holder) {
        seeder.seedWorkingWithClaim(issueFor(ref), holder);
    }

    /**
     * Simulates a human moving a parked issue back to {@code Ready} directly in the
     * tracker UI, per {@code TrackerReturnedFactContract.returnToReady}: swaps the
     * needs-human label for the ready label, exactly as a human's own edit would,
     * without touching any comment — the park report and prior claim markers stay in
     * the issue's history for an adapter's returned-fact derivation to observe.
     */
    void returnToReady(TaskRef ref) {
        FixtureIssue issue = issueFor(ref);
        issue.removeLabel(FixtureSeeder.NEEDS_HUMAN_LABEL);
        issue.addLabel(FixtureSeeder.READY_LABEL);
    }
}
