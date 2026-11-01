package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.app.describe.ProgramBounds;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import java.time.Duration;
import java.time.Instant;

/**
 * The wait of a {@code program} run whose describe declares {@code streams_progress}, under two
 * bounds (NFR-R2 of define-executor-contract; wire-contract "Silence bound expires"): the overall
 * bound from the start, and the silence bound from the last bytes the progress reader saw. The
 * handle's own bounded wait kills on expiry, so it cannot be waited on in slices; this wait instead
 * watches the reader, which ends at the process's exit, and checks both deadlines on the run's clock
 * at least every {@link #TICK}. Each slice is spent first in a bounded join on the reader — which
 * returns at once when the stream ends — and then in the time equipment's {@code sleepUntil} the
 * slice's end, which returns at once on real time (the join already spent the slice) and advances
 * a virtual clock under test: a spec reaches a deadline without waiting for it, and a stream that
 * ends is seen whichever thread runs first. A deadline reached is a kill through the handle — a
 * wait with no time left, which kills the tree and reaps it, or reports the exit when the process
 * just ended — and the bound it names.
 *
 * <p>An interrupt during a sleep kills the tree the same way and reports {@link
 * ProgramEnd.Interrupted} with the flag left set.
 *
 * <p>Implements NFR-R2 of define-executor-contract.
 */
final class ProgressWait {

    /** The longest the wait goes without looking at the reader and the deadlines. */
    static final Duration TICK = Duration.ofMillis(100);

    private final ProgramBounds bounds;
    private final TimeEquipment time;

    /**
     * @param bounds the run's bounds; never null
     * @param time the run's time equipment, shared with the progress reader's clock; never null
     */
    ProgressWait(ProgramBounds bounds, TimeEquipment time) {
        this.bounds = bounds;
        this.time = time;
    }

    /**
     * Waits for the run to end.
     *
     * @param handle the run's process; never null
     * @param lines the run's progress reader; never null
     * @return how the run ended; never null
     */
    ProgramEnd await(ExecHandle handle, ProgressLines lines) {
        Instant overallEnd = time.clock().instant().plus(bounds.overall());
        while (!lines.ended()) {
            Instant silentUntil = lines.lastActivity().plus(bounds.silence());
            if (time.remaining(overallEnd).isZero()) {
                return killed(handle, new ProgramEnd.BoundExpired("overall", bounds.overall()));
            }
            if (time.remaining(silentUntil).isZero()) {
                return killed(handle, new ProgramEnd.BoundExpired("silence", bounds.silence()));
            }
            Instant next = earliest(time.clock().instant().plus(TICK), earliest(overallEnd, silentUntil));
            if (lines.endsWithin(time.remaining(next))) {
                break;
            }
            time.sleepUntil(next);
            if (Thread.currentThread().isInterrupted()) {
                return killed(handle, new ProgramEnd.Interrupted());
            }
        }
        return ended(handle.waitForExitOrTimeout(time.remaining(overallEnd), time.clock()), handle);
    }

    /** Kills the tree now; a process that exited in the meantime is reported as the exit it was. */
    private ProgramEnd killed(ExecHandle handle, ProgramEnd reached) {
        ExecHandle.Wait wait = handle.waitForExitOrTimeout(Duration.ZERO, time.clock());
        return wait instanceof ExecHandle.Wait.Exited exited
                ? new ProgramEnd.Exited(handle.waitForExit(), exited.wallTime())
                : reached;
    }

    private ProgramEnd ended(ExecHandle.Wait wait, ExecHandle handle) {
        return switch (wait) {
            case ExecHandle.Wait.Exited exited -> new ProgramEnd.Exited(handle.waitForExit(), exited.wallTime());
            case ExecHandle.Wait.TimedOut ignored -> new ProgramEnd.BoundExpired("overall", bounds.overall());
            case ExecHandle.Wait.Interrupted ignored -> new ProgramEnd.Interrupted();
        };
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
