package com.github.oinsio.gnomish.adapter.tracker.inmemory

import com.github.oinsio.gnomish.app.port.tracker.AbortFacts
import com.github.oinsio.gnomish.app.port.tracker.HumanReply
import com.github.oinsio.gnomish.app.port.tracker.TaskRef
import com.github.oinsio.gnomish.app.port.tracker.TaskSnapshot
import com.github.oinsio.gnomish.app.port.tracker.Tracker
import com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState
import com.github.oinsio.gnomish.app.port.tracker.contract.TrackerFetchContract

/**
 * Wires the production {@link InMemoryTracker} (via {@link
 * InMemoryTrackerHarness}) into the full port contract suite (task 2.7,
 * FR3, FR4, M1, M2): {@link TrackerFetchContract} transitively runs every
 * property from {@code TrackerContract}, {@code TrackerMarkerContract}, and
 * itself against this one adapter, with zero adapter-specific exemptions.
 *
 * <p>Spock instantiates a fresh spec instance per feature method by default,
 * so {@link #arrange} runs once per property and the {@link #harness} field
 * it sets is always the harness for the adapter instance that same property's
 * {@code seedTask}/{@code seedReply} calls receive back as {@code adapter} —
 * no cross-feature sharing, matching the precedent in {@code
 * InMemoryAttemptPersistenceContractSpec}.
 *
 * <p>Implements FR3, FR4 of add-tracker-port.
 */
class InMemoryTrackerContractSpec extends TrackerFetchContract {

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
}
