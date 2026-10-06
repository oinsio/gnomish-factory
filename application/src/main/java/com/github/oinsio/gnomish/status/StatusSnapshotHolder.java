package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.TaskState;

/**
 * The live event fold of one run: the {@link TaskState} the last finished round persisted, kept
 * current by a {@link StatusEventListener} as {@code AttemptFinished} events arrive (design D7 of
 * add-manual-run).
 *
 * <p>One production reader: the run's check context reads the current stage from {@link
 * #state()}. The holder builds no report and holds nothing else: a task's status is built from its
 * persisted state by {@code gnomish status} alone (design D4 of make-run-headless).
 *
 * <p>Reads and writes are synchronized: the fold is written on the engine's event thread and read
 * by the check context, so each accessor publishes the field safely. No accessor does anything but
 * a field read or write.
 *
 * <p>Implements FR10, D7 of add-manual-run; FR6 of make-run-headless.
 */
public final class StatusSnapshotHolder {

    private TaskState state;

    /**
     * Creates a fold starting at {@code initialState} — the natural starting point before any
     * engine event has arrived.
     *
     * @param initialState the task's state before any event is observed; never null
     */
    public StatusSnapshotHolder(TaskState initialState) {
        this.state = initialState;
    }

    /**
     * Replaces the held {@link TaskState}, called by the listener on {@code AttemptFinished}.
     *
     * @param newState the state after the finished attempt; never null
     */
    public synchronized void updateState(TaskState newState) {
        this.state = newState;
    }

    /** The currently held {@link TaskState}. */
    public synchronized TaskState state() {
        return state;
    }
}
