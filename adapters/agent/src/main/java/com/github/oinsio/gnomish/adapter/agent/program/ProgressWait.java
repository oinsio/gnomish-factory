package com.github.oinsio.gnomish.adapter.agent.program;

import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The wait of a run whose stdout is read live by {@link ProgressLines} (NFR-R2 of
 * define-executor-contract; wire-contract "Silence bound expires"; NFR-R1 of
 * extract-agent-round-runner), under up to three bounds: the overall bound from the start; when one is
 * given, the startup-read bound from the start until the first bytes arrive; and the silence bound from
 * the last bytes the reader saw — from the start too when no startup-read bound is given. A {@code
 * streams_progress} program waits without a startup-read bound, the agent round runner with one.
 *
 * <p>The handle's own bounded wait kills on expiry, so it cannot be waited on in slices; this wait
 * instead watches the reader, which ends at the process's exit, and checks the deadlines on the run's
 * clock at least every {@link #TICK}. Each slice is spent first in a bounded join on the reader —
 * which returns at once when the stream ends — and then in the time equipment's {@code sleepUntil} the
 * slice's end, which returns at once on real time (the join already spent the slice) and advances
 * a virtual clock under test: a spec reaches a deadline without waiting for it, and a stream that
 * ends is seen whichever thread runs first. A deadline reached is a kill through the handle — a
 * wait with no time left, which kills the tree and reaps it, or reports the exit when the process
 * just ended — and the bound it names.
 *
 * <p>An interrupt during a sleep kills the tree the same way and reports {@link
 * ProgramEnd.Interrupted} with the flag left set.
 *
 * <p>Implements NFR-R2 of define-executor-contract; NFR-R1 of extract-agent-round-runner.
 */
public final class ProgressWait {

    /** The longest the wait goes without looking at the reader and the deadlines. */
    static final Duration TICK = Duration.ofMillis(100);

    private final @Nullable Duration startupRead;
    private final Duration silence;
    private final Duration overall;
    private final TimeEquipment time;

    /**
     * @param startupRead how long the run may take to write its first bytes, or {@code null} to hold
     *     the start to the silence bound instead
     * @param silence how long the run may go without writing; never null
     * @param overall how long the run may take at all; never null
     * @param time the run's time equipment, shared with the reader's clock; never null
     */
    public ProgressWait(@Nullable Duration startupRead, Duration silence, Duration overall, TimeEquipment time) {
        this.startupRead = startupRead;
        this.silence = silence;
        this.overall = overall;
        this.time = time;
    }

    /**
     * Waits for the run to end.
     *
     * @param handle the run's process; never null
     * @param lines the run's stdout reader; never null
     * @return how the run ended; never null
     */
    public ProgramEnd await(ExecHandle handle, ProgressLines lines) {
        Instant start = time.clock().instant();
        Instant overallEnd = start.plus(overall);
        while (!lines.ended()) {
            @Nullable Duration first = lines.anyRead() ? null : startupRead;
            Instant quietUntil = first == null ? lines.lastActivity().plus(silence) : start.plus(first);
            if (time.remaining(overallEnd).isZero()) {
                return killed(handle, new ProgramEnd.BoundExpired("overall", overall));
            }
            if (time.remaining(quietUntil).isZero()) {
                return killed(
                        handle,
                        first == null
                                ? new ProgramEnd.BoundExpired("silence", silence)
                                : new ProgramEnd.BoundExpired("startup-read", first));
            }
            Instant next = earliest(time.clock().instant().plus(TICK), earliest(overallEnd, quietUntil));
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
            case ExecHandle.Wait.TimedOut ignored -> new ProgramEnd.BoundExpired("overall", overall);
            case ExecHandle.Wait.Interrupted ignored -> new ProgramEnd.Interrupted();
        };
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
