package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.OpenTask;
import java.util.List;

/**
 * A tap on {@link Reaper}'s per-tick {@code listOpen} call (design D1 of
 * add-serve-sandbox-lifecycle): {@link Reaper#reapOnce} publishes its own listing (or its
 * failure) here BEFORE excluding the instance's own claims, so a consumer sees exactly the
 * listing the reaper acted on — no second {@code listOpen} call, honoring NFR-C2 ("at most one
 * tracker open-task listing... shareable with the claim reaper's existing listing"). {@link
 * CachedOpenTaskListing} is the real sink the liveness oracle (task 2.1) reads from; {@link
 * #NONE} lets every existing {@link Reaper} caller keep constructing it without a sink.
 *
 * <p>Implements FR3, NFR-C2 of add-serve-sandbox-lifecycle.
 */
public interface OpenTaskListingSink {

    /** The no-op sink: every pre-existing {@link Reaper} construction site is unaffected. */
    OpenTaskListingSink NONE = new OpenTaskListingSink() {
        @Override
        public void onListed(List<OpenTask> openTasks) {}

        @Override
        public void onListingFailed() {}
    };

    /**
     * A successful {@code listOpen} this tick, before the reaper excludes its own claims.
     *
     * @param openTasks the full listing; never null
     */
    void onListed(List<OpenTask> openTasks);

    /** A {@code listOpen} outage this tick — the reaper forgot its observation windows. */
    void onListingFailed();
}
