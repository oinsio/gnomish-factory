package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.app.port.tracker.BoundaryKind;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;

/**
 * Whether a task's finish already landed on the tracker, read from the task's history: it landed
 * iff the task's <b>tenure boundary</b> — the thread's last boundary marker, whatever claims follow
 * it — is {@code FINISH}, or the adapter reports the {@code finished} fact. Never the current
 * state: every pickup runs after its own claim, which has already made it {@code Working}.
 *
 * <p>The one finish reading: {@link FinishEffect#observeAtTarget}, the protocol's probe, reads it,
 * and so does the claimless cleanup of a delivered branch (design D5). It is a pure predicate over
 * a task already fetched, so each caller keeps its own fetch and its own failure handling.
 *
 * <p>Implements FR3 of fix-terminal-receipt-convergence.
 */
public final class FinishLanding {

    private FinishLanding() {}

    /**
     * Returns true when the task's finish has landed on the tracker.
     *
     * @param task the task as {@code fetchTask} reported it; never null
     * @return true when the task's finish has landed on the tracker
     */
    public static boolean landed(TrackerTask task) {
        return task.finished() || task.tenureBoundary() == BoundaryKind.FINISH;
    }
}
