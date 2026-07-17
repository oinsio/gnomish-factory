package com.github.oinsio.gnomish.adapter.console.fake

import com.github.oinsio.gnomish.adapter.console.ActivityTracker
import com.github.oinsio.gnomish.status.Activity
import org.jspecify.annotations.Nullable

/**
 * A test {@link ActivityTracker} that starts at a caller-supplied activity and
 * records every mark/restore call in order, so specs can assert both the
 * begin/end sequence and that the prior activity is restored (FR10, D7).
 *
 * <p>Test fake for the add-manual-run ports; not production code, never
 * PIT-mutated.
 */
class RecordingActivityTracker implements ActivityTracker {

    private @Nullable Activity current

    /** Every activity passed to {@link #restore}, in call order. */
    final List<Activity> restoredTo = []

    /** Every prompt text passed to {@link #markAwaitingInput}, in call order. */
    final List<String> prompts = []

    /** Number of times {@link #markAwaitingInput} has been called. */
    int markCount = 0

    RecordingActivityTracker(@Nullable Activity initial = null) {
        this.current = initial
    }

    /** The activity currently held, for asserting the state during a blocking read. */
    @Nullable
    Activity current() {
        current
    }

    @Override
    @Nullable
    Activity markAwaitingInput(String prompt) {
        markCount++
        prompts << prompt
        def previous = current
        current = new Activity.AwaitingInput(prompt, java.time.Instant.EPOCH)
        previous
    }

    @Override
    void restore(@Nullable Activity previousActivity) {
        current = previousActivity
        restoredTo << previousActivity
    }
}
