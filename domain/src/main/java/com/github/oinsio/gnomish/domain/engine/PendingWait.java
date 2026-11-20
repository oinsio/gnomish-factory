package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * The clock of one check's wait (design D2, D10, D19 of migrate-verify-to-executor-contract): the
 * deadline at the entry's {@code since} plus the check's timeout, the time elapsed and remaining on
 * the injected clock, and the wait before each next poll. After a pending answer the next poll comes
 * no sooner than {@code max(interval, pollAfter)}; the interval is the in-poll wait bound, carried to
 * the executor by the check on the request. A hint reaching past the deadline ends the wait at the
 * deadline with no further poll; a hint never extends the timeout. It sleeps on the equipment's
 * sleeper, and its caller holds nothing while it does (D10).
 *
 * <p>One instance per wait, confined to the thread that runs it ({@link PendingPoll}).
 *
 * <p>Implements FR2, FR4, NFR-R1 of migrate-verify-to-executor-contract.
 */
final class PendingWait {

    private final TimeEquipment time;
    private final Duration interval;
    private final Instant since;
    private final Instant deadline;

    /**
     * @param time the clock the deadline is measured on and the sleeper waited on; never null
     * @param check the check whose interval and timeout bound the wait; never null
     * @param since when the check's entry was first recorded pending; never null
     */
    PendingWait(TimeEquipment time, VerifyCheck.Executor check, Instant since) {
        this.time = time;
        this.interval = check.interval();
        this.since = since;
        this.deadline = since.plus(check.timeout());
    }

    /** The time since the entry's {@code since}, on the injected clock. */
    Duration elapsed() {
        return Duration.between(since, time.clock().instant());
    }

    /** The time left before the deadline; zero once it has passed. */
    Duration remaining() {
        return time.remaining(deadline);
    }

    /**
     * Waits before the next poll: false when the deadline ends the wait instead, either because no
     * time remains or because the answer's hint reaches past it (then the wait ends at the deadline).
     *
     * @param last the pending answer the next poll waits behind; never null
     * @return true when the next poll may be made
     */
    boolean waited(ExecutionResult.Pending last) {
        var remaining = remaining();
        if (remaining.isZero()) {
            return false;
        }
        var hint = last.pollAfter();
        if (hint != null && hint.compareTo(remaining) > 0) {
            time.sleepUntil(deadline);
            return false;
        }
        time.sleeper().sleep(hint == null ? interval : Collections.max(List.of(interval, hint)));
        return true;
    }
}
