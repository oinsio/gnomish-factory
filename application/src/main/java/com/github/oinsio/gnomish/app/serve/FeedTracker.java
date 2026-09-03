package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.FeedPolicy;
import java.util.List;

/**
 * The tracker as one factory instance sees it: the three reads and the one write {@link FeedCycle}
 * makes, with the identity every claim is made under bound in rather than threaded through the
 * cycle as a parameter it forwards to a single call.
 *
 * <p>{@link #tracker()} stays reachable because {@code FinishedDecline} takes the port itself — it
 * sweeps on behalf of the cycle rather than through it, and wrapping that would invert the
 * ownership rather than simplify it.
 *
 * <p>Implements FR5, FR9 of add-factory-serve.
 *
 * @param tracker the tracker port; never null
 * @param instanceId this factory instance's identity, carried on every claim; never null
 */
record FeedTracker(Tracker tracker, InstanceId instanceId) {

    /**
     * One feed read, bounded by {@link FeedPolicy#FEED_LIMIT}.
     *
     * @return the ready tasks the tracker handed back; never null
     */
    List<ReadyTask> listReady() {
        return tracker.listReady(FeedPolicy.FEED_LIMIT);
    }

    /**
     * How many tasks this instance currently has open — the open-front count both gradings apply.
     *
     * @return the count, never negative
     */
    int openFrontCount() {
        return tracker.listOpen().size();
    }

    /**
     * Claims {@code ref} for this instance.
     *
     * @param ref the task to claim; never null
     * @return the tracker's verdict; never null
     */
    ClaimResult claim(TaskRef ref) {
        return tracker.claim(ref, instanceId.value());
    }
}
