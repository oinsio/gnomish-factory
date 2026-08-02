package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;

/**
 * The seam between the feed automaton and the slot body (scope boundary of task 4.2 vs 4.3): the
 * automaton claims a task and hands off only its identity — everything the take cycle needs
 * (clone dir, pipeline definition, interactive mode, the {@code Tracker}, the instance id,
 * heartbeat registration) is the real implementation's own closure state, wired by whatever
 * assembles {@link FeedAutomaton} (a later task). {@link FeedAutomaton} spawns one virtual thread
 * per slot and calls {@link #run(TaskRef)} on it (design D1); it releases the {@link
 * SlotLedger} permit itself once {@link #run(TaskRef)} returns (normally or by exception), so an
 * implementation never needs to know the ledger exists.
 *
 * <p>A test double can be a blocking or latch-driven lambda, letting a spec prove the automaton
 * moves on to its next cycle without waiting for a slot's task to finish (the concurrency design
 * D1 exists for).
 *
 * <p>Implements D1 of add-factory-serve.
 */
@FunctionalInterface
public interface SlotRunner {

    /**
     * Runs the already-claimed task {@code claimed} to its terminal result. Any exception
     * propagates to {@link FeedAutomaton}'s wrapping thread, which still releases the slot
     * permit — a slot body that throws never leaks capacity.
     *
     * @param claimed the just-claimed task's identity; never null
     */
    void run(TaskRef claimed);
}
