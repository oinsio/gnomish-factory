package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.port.tracker.contract.TrackerReapContract

/**
 * Wires the production {@link InMemoryTracker} (via {@link
 * InMemoryTrackerHarness}) into the full port contract suite (task 2.4/2.7,
 * FR3, FR4, FR5, M1, M2): {@link TrackerReapContract} — the most-derived link
 * in the lease chain — transitively runs every property from {@code
 * TrackerContract}, {@code TrackerMarkerContract}, {@code TrackerFetchContract},
 * {@code TrackerLeaseContract}, {@code TrackerHeartbeatContract}, and itself
 * against this one adapter, with zero adapter-specific exemptions.
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
class InMemoryTrackerContractSpec extends TrackerReapContract {

    private InMemoryTrackerHarness harness

    @Override
    protected Optional<Tracker> arrange() {
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
}
