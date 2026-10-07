package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.EngineEvent;
import com.github.oinsio.gnomish.domain.engine.port.EngineEventListener;

/**
 * The {@link EngineEventListener} adapter that keeps a {@link StatusSnapshotHolder} current as the
 * engine's event stream arrives (design D7 of add-manual-run): {@code AttemptFinished} carries the
 * state the round persisted, and the fold takes it — so at every attempt boundary the fold holds
 * exactly the persisted round's {@code TaskState}.
 *
 * <p>Every other event is a no-op: none carries a state change, and nothing in flight is tracked
 * — no reader of the fold needs it, and a task's escalation and outcome are read from its persisted
 * record by {@code gnomish status}, never from the fold (design D4 of make-run-headless).
 *
 * <p>The one branch here is a plain field update on the holder with no I/O, so this class naturally
 * satisfies the port's "never throw past {@code onEvent}" contract without defensive exception
 * handling.
 *
 * <p>Implements FR10, FR11, D7 of add-manual-run; FR6 of make-run-headless.
 */
public final class StatusEventListener implements EngineEventListener {

    private final StatusSnapshotHolder holder;

    /**
     * Wraps {@code holder}, the fold this listener keeps current.
     *
     * @param holder the snapshot holder to update on each finished attempt; never null
     */
    public StatusEventListener(StatusSnapshotHolder holder) {
        this.holder = holder;
    }

    /**
     * Updates the wrapped {@link StatusSnapshotHolder} with the state an {@code AttemptFinished}
     * carries; every other event is ignored.
     *
     * <p>Implements FR10, FR11, D7 of add-manual-run.
     *
     * @param event the event that just occurred; never null
     */
    @Override
    public void onEvent(EngineEvent event) {
        if (event instanceof EngineEvent.AttemptFinished finished) {
            holder.updateState(finished.newState());
        }
    }
}
