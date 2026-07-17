package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.adapter.console.ActivityTracker;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import org.jspecify.annotations.Nullable;

/**
 * The {@link ActivityTracker} that bridges {@code DialogConsole} prompt
 * markers onto a {@link StatusSnapshotHolder} (design D7 of add-manual-run):
 * the engine has no notion of a human being prompted, so
 * {@code DialogConsole} — the single input choke point, design D1 — marks
 * an {@link Activity.AwaitingInput} directly around each blocking read
 * through this bridge, restoring whatever activity was current beforehand
 * once the read returns.
 *
 * <p>Implements FR10, D7 of add-manual-run.
 */
public final class SnapshotActivityTracker implements ActivityTracker {

    private final StatusSnapshotHolder holder;
    private final Clock clock;

    /**
     * Wraps {@code holder}, the snapshot this tracker marks and restores, and
     * {@code clock}, the source of the {@code since} instant stamped on the
     * {@link Activity.AwaitingInput} it marks.
     *
     * @param holder the snapshot holder to update around each prompt; never null
     * @param clock the engine's injected time source; never null
     */
    public SnapshotActivityTracker(StatusSnapshotHolder holder, Clock clock) {
        this.holder = holder;
        this.clock = clock;
    }

    @Override
    public @Nullable Activity markAwaitingInput(String prompt) {
        Activity previous = holder.activity().activity();
        holder.updateActivity(new Activity.AwaitingInput(prompt, clock.now()));
        return previous;
    }

    @Override
    public void restore(@Nullable Activity previousActivity) {
        holder.updateActivity(previousActivity);
    }
}
