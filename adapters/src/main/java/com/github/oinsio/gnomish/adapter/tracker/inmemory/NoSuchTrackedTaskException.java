package com.github.oinsio.gnomish.adapter.tracker.inmemory;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.io.Serial;

/**
 * Thrown by {@link InMemoryTracker} when a mutating or fact-collecting
 * operation other than {@link com.github.oinsio.gnomish.app.port.tracker.Tracker#fetchTask}
 * is called against a {@link TaskRef} the store has never seeded. {@code
 * fetchTask} is the one operation the port contract requires to report an
 * unknown task as {@link com.github.oinsio.gnomish.app.port.tracker.TrackerTaskState.Gone}
 * rather than throw (tracker-port spec, "Closed task is Gone"); every other
 * operation presupposes a task the caller already knows exists (from a prior
 * {@code listReady} or {@code fetchTask}), so calling it against an unseeded
 * ref is a programming error in the caller, not a fact the port models.
 *
 * <p>Implements FR1 of add-tracker-port.
 */
public final class NoSuchTrackedTaskException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param ref the task reference the in-memory store has no entry for
     */
    public NoSuchTrackedTaskException(TaskRef ref) {
        super("no tracked task for ref \"" + ref.id() + "\"");
    }
}
