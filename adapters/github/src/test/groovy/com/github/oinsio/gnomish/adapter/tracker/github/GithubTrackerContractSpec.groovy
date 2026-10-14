package com.github.oinsio.gnomish.adapter.tracker.github

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.AbortRecord
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.port.tracker.contract.TrackerCirculationContract
import com.github.oinsio.gnomish.untrustedtext.UntrustedText
import java.time.Instant

/**
 * Wires the REAL production {@link GithubTracker} (composing the real {@link
 * GithubFeedQuery}, {@link GithubTaskFetcher}, {@link GithubClaimLease},
 * {@link GithubStateWrites}, {@link GithubCorrespondence}, {@link
 * GithubDecisions} — every one of them unmodified) against a WireMock server
 * into the full port contract suite (task 4.16 / task 3.5 / 1.2 of
 * add-factory-serve, FR4, M1, M2): {@link TrackerCirculationContract} — the
 * most-derived link in the chain — transitively runs every property from
 * {@code TrackerContract}, {@code TrackerMarkerContract}, {@code
 * TrackerFetchContract}, {@code TrackerLeaseContract}, {@code
 * TrackerHeartbeatContract}, {@code TrackerReapContract}, and itself against
 * this one adapter, with zero adapter-specific exemptions — the SAME whole
 * suite the in-memory reference passes (M1), including the reap/beat race
 * scenarios (M2).
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
 * <p>The wiring of the real adapter, and the out-of-band edits a human makes in the tracker UI,
 * live in {@link GithubContractTracker}; this spec binds the contract's seams to them.
 *
 * <p>Implements FR4, NFR-R1 of add-tracker-port; FR1, FR4, FR5, FR8, NFR-R2,
 * M1 of add-claim-heartbeat (the extended contract passes on the GitHub adapter).
 */
// Scenario: tracker/github-tracker / Decline comment is derivation-neutral
// Scenario: tracker/github-tracker / Abort facts read back on any instance
// Scenario: tracker/github-tracker / Ready issue with a live claim is visible to the sweep
// Scenario: tracker/tracker-port / Suite passes on both adapters
// Scenario: tracker/tracker-port / Returned-fact suite passes on both adapters
// Scenario: tracker/tracker-port / The suite covers the open listing and its facts
// Scenario: tracker/tracker-port / The suite covers the heartbeat
// Scenario: tracker/tracker-port / Finish-reopen property is adapter-equivalent
// Scenario: tracker/tracker-port / The suite covers the decline operation
// Scenario: tracker/tracker-port / The suite binds the open-front count to the open listing
// Scenario: tracker/tracker-port / Contract suite binds title propagation
// Scenario: tracker/tracker-port / Conflict lists every value
// Scenario: tracker/tracker-port / The contract suite covers every designator shape
// Scenario: tracker/tracker-port / The suite covers stale-claim removal
// Scenario: tracker/tracker-port / The suite covers index repair
class GithubTrackerContractSpec extends TrackerCirculationContract {

    private GithubContractTracker github
    private GithubTrackerFixtureAdapter fixtureAdapter

    def setup() {
        github = new GithubContractTracker()
        fixtureAdapter = github.adapter
    }

    def cleanup() {
        github.stop()
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

    @Override
    protected void seedWorkingWithClaim(Tracker adapter, TaskRef ref, String holder) {
        fixtureAdapter.seedWorkingWithClaim(ref, holder)
    }

    @Override
    protected void seedWorkingWithoutClaim(Tracker adapter, TaskRef ref) {
        fixtureAdapter.seedWorkingWithoutClaim(ref)
    }

    @Override
    protected void seedWorkingWithDeadClaim(Tracker adapter, TaskRef ref, String holder) {
        // A GitHub dead footprint is a claim comment a later boundary marker voided: the real
        // adapter's own abort write posts that marker, and the rewind puts the working label back.
        fixtureAdapter.seedWorkingWithClaim(ref, holder)
        adapter.recordAbort(ref, new AbortRecord(UntrustedText.subprocess('lost'), holder, Instant.parse('2026-07-20T10:00:00Z')))
        github.rewindIndexToWorking(ref)
    }

    @Override
    protected void rewindIndexToWorking(Tracker adapter, TaskRef ref) {
        github.rewindIndexToWorking(ref)
    }

    @Override
    protected void returnToReady(Tracker adapter, TaskRef ref) {
        fixtureAdapter.returnToReady(ref)
    }

    @Override
    protected void reopenFinished(Tracker adapter, TaskRef ref) {
        fixtureAdapter.reopenFinished(ref)
    }

    @Override
    protected List<String> postedTexts(Tracker adapter, TaskRef ref) {
        fixtureAdapter.postedTexts(ref)
    }

    @Override
    protected void seedDesignatorCandidates(Tracker adapter, TaskRef ref, String kind, List<String> values) {
        fixtureAdapter.seedDesignatorCandidates(ref, kind, values)
    }
}
