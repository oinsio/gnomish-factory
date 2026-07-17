package com.github.oinsio.gnomish.adapter.console;

import com.github.oinsio.gnomish.status.Activity;
import org.jspecify.annotations.Nullable;

/**
 * The seam {@link DialogConsole} calls to mark the begin/end of a blocking
 * prompt read (design D7): the engine has no notion of a human being
 * prompted — only {@code DialogConsole}, the single input choke point
 * (design D1), knows a prompt is pending — so it marks an {@link
 * Activity.AwaitingInput} directly around each blocking read through this
 * bridge, restoring whatever activity was current beforehand once the read
 * returns. Kept as a small interface here, mirroring {@link StatusRenderer},
 * so {@code DialogConsole} depends only on the {@code status} package's
 * {@link Activity} shape, not on its concrete {@code StatusSnapshotHolder}.
 *
 * <p>Implements FR10, D7 of add-manual-run.
 */
public interface ActivityTracker {

    /**
     * A tracker that observes nothing and reports {@code null} (no activity) as
     * the "previous" activity — the default for call sites that predate
     * live-activity tracking (task 6.3) and are not yet wired to a
     * {@code StatusSnapshotHolder} (task 7.11).
     */
    ActivityTracker NONE = new ActivityTracker() {
        @Override
        public @Nullable Activity markAwaitingInput(String prompt) {
            return null;
        }

        @Override
        public void restore(@Nullable Activity previousActivity) {
            // No-op: nothing was marked, so there is nothing to restore.
        }
    };

    /**
     * Marks the tracked activity as {@link Activity.AwaitingInput} naming {@code
     * prompt}, just before a blocking read.
     *
     * @param prompt the prompt text the operator is being asked; never null
     * @return the activity that was current before this call, to be passed back
     *     to {@link #restore(Activity)}
     */
    @Nullable
    Activity markAwaitingInput(String prompt);

    /**
     * Restores the activity captured by a prior {@link #markAwaitingInput(String)}
     * call, once the blocking read has returned (normally or via EOF).
     *
     * @param previousActivity the activity returned by the paired {@link
     *     #markAwaitingInput(String)} call
     */
    void restore(@Nullable Activity previousActivity);
}
