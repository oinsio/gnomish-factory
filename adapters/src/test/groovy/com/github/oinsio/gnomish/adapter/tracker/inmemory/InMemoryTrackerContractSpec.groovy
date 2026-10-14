package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.Designator
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.TaskDesignators
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.port.tracker.contract.TrackerCirculationContract
import com.github.oinsio.gnomish.untrustedtext.UntrustedText

/**
 * Wires the production {@link InMemoryTracker} (via {@link
 * InMemoryTrackerHarness}) into the full port contract suite (task 2.4/2.7,
 * 1.2 of add-factory-serve; FR3, FR4, FR5, M1, M2): {@link
 * TrackerCirculationContract} — the most-derived link in the chain —
 * transitively runs every property from {@code TrackerContract}, {@code
 * TrackerMarkerContract}, {@code TrackerFetchContract}, {@code
 * TrackerLeaseContract}, {@code TrackerHeartbeatContract}, {@code
 * TrackerReapContract}, and itself against this one adapter, with zero
 * adapter-specific exemptions.
 *
 * <p>Spock instantiates a fresh spec instance per feature method by default,
 * so {@link #arrange} runs once per property and the {@link #harness} field
 * it sets is always the harness for the adapter instance that same property's
 * {@code seedTask}/{@code seedReply}/{@code seedWorkingWithClaim} calls receive
 * back as {@code adapter} — no cross-feature sharing, matching the precedent in
 * {@code InMemoryAttemptPersistenceContractSpec}.
 *
 * <p>Implements FR3, FR4, FR5 of add-tracker-port and add-claim-heartbeat;
 * M1 of add-claim-heartbeat (the extended contract passes on this adapter).
 */
// Scenario: tracker/tracker-port / Index repair restores a claimless working task to Ready
// Scenario: tracker/tracker-port / Full fact set for a working task
// Scenario: tracker/tracker-port / Closed task is Gone
// Scenario: tracker/tracker-port / Ack consumes decisions
// Scenario: tracker/tracker-port / Stale replies never resurface
// Scenario: tracker/tracker-port / Fresh instance sees abort history
// Scenario: tracker/tracker-port / Progress reset round-trips across instances
// Scenario: tracker/tracker-port / Abort facts carry no adapter policy
// Scenario: tracker/tracker-port / Reference passes the contract suite
// Scenario: tracker/tracker-port / Race simulation
// Scenario: tracker/tracker-port / Feed filtering property
// Scenario: tracker/tracker-port / Claim atomicity property
// Scenario: tracker/tracker-port / Listing carries versions
// Scenario: tracker/tracker-port / Version changes are observable across instances
// Scenario: tracker/tracker-port / Uninterpretable combinations are reported as facts
// Scenario: tracker/tracker-port / Ready entry carries claim facts
// Scenario: tracker/tracker-port / Beat refreshes the version
// Scenario: tracker/tracker-port / Gone claim is a signal, not an error
// Scenario: tracker/tracker-port / Beat of a task the tracker no longer holds is claim-gone
// Scenario: tracker/tracker-port / Removal round-trip
// Scenario: tracker/tracker-port / Version mismatch is a no-op
// Scenario: tracker/tracker-port / Removal of a task the tracker no longer holds is a no-op
// Scenario: tracker/tracker-port / Concurrent removal race
// Scenario: tracker/tracker-port / Reopened finished task carries the fact
// Scenario: tracker/tracker-port / Decline round-trip
// Scenario: tracker/tracker-port / Decline of an already-terminal task is a no-op
// Scenario: tracker/tracker-port / Decline does not pollute derivations
// Scenario: tracker/tracker-port / Fresh task is not returned
// Scenario: tracker/tracker-port / Park round-trip sets the fact
// Scenario: tracker/tracker-port / Reaped task is returned
// Scenario: tracker/tracker-port / Finish-reopen is not returned
// Scenario: tracker/tracker-port / The open-front count is the open listing's size
// Scenario: tracker/tracker-port / Ready listing carries titles
// Scenario: tracker/tracker-port / Open listing carries titles
// Scenario: tracker/tracker-port / Reclaim returns a greater token
// Scenario: tracker/tracker-port / Token is observable by other instances
// Scenario: tracker/tracker-port / Absent is absent, not empty
// Scenario: tracker/tracker-port / Equal duplicates are one designator
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
// Scenario: tracker/tracker-port / Index repair never claims the task for the caller
// Scenario: tracker/tracker-port / Parked task returns only through a human
// Scenario: tracker/tracker-port / Racing declines leave the task terminal
// Scenario: tracker/tracker-port / The returned fact carries no prioritization
// Scenario: tracker/tracker-port / An adapter that does not stamp epochs is still constructible
class InMemoryTrackerContractSpec extends TrackerCirculationContract {

    private InMemoryTrackerHarness harness

    @Override
    protected Optional<Tracker> arrange() {
        // FR13 of harden-task-branch-contract: no tenure record is handed in — the reference
        // adapter stamps no epochs, and the claim-token links of this suite still bind it.
        harness = new InMemoryTrackerHarness(new InMemoryTracker())
        Optional.of(harness.tracker())
    }

    @Override
    protected void seedTask(Tracker adapter, TaskRef ref, TaskSnapshot snapshot, TrackerTaskState state, AbortFacts abortFacts) {
        harness.seed(ref, snapshot, state, abortFacts)
    }

    @Override
    protected void seedReply(Tracker adapter, TaskRef ref, HumanReply reply) {
        harness.seedReply(adapter, ref, reply)
    }

    @Override
    protected void seedWorkingWithClaim(Tracker adapter, TaskRef ref, String holder) {
        harness.seedWorkingWithClaim(adapter, ref, holder)
    }

    @Override
    protected void seedWorkingWithoutClaim(Tracker adapter, TaskRef ref) {
        // The reference adapter's own working state carries no marker unless one is established,
        // so seeding the state alone IS the claimless window.
        harness.seed(ref, new TaskSnapshot(ref.id(), UntrustedText.tracker('fixture title'), UntrustedText.tracker('fixture body')),
                new TrackerTaskState.Working('instance-a'), AbortFacts.none())
    }

    @Override
    protected void seedWorkingWithDeadClaim(Tracker adapter, TaskRef ref, String holder) {
        // The reference adapter's dead footprint is a claim whose marker a release cleared while
        // the task stays working: both steps are its own port operations.
        harness.seed(ref, new TaskSnapshot(ref.id(), UntrustedText.tracker('fixture title'), UntrustedText.tracker('fixture body')),
                new TrackerTaskState.Ready(), AbortFacts.none())
        adapter.claim(ref, holder)
        adapter.release(ref)
    }

    @Override
    protected void rewindIndexToWorking(Tracker adapter, TaskRef ref) {
        // A state edit under the tracker's own lock: the recorded thread stays as it was.
        def tracker = harness.adapter()
        tracker.withLock({
            ->
            tracker.store.get(ref).state(new TrackerTaskState.Working('instance-a'))
            null
        })
    }

    @Override
    protected void returnToReady(Tracker adapter, TaskRef ref) {
        harness.returnToReady(ref)
    }

    @Override
    protected void reopenFinished(Tracker adapter, TaskRef ref) {
        harness.reopenFinished(ref)
    }

    @Override
    protected List<String> postedTexts(Tracker adapter, TaskRef ref) {
        harness.thread(ref)*.text()
    }

    @Override
    protected void seedDesignatorCandidates(Tracker adapter, TaskRef ref, String kind, List<String> values) {
        // The reference tracker has no labels and no native fields to derive candidates from, so
        // its own "representation" of them is the port's shared classification applied directly —
        // which is exactly the obligation every adapter carries: classify, never resolve.
        harness.seedDesignators(ref, TaskDesignators.of(kind, Designator.classify(values)))
    }
}
