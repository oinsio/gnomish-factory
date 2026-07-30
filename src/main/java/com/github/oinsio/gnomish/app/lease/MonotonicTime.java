package com.github.oinsio.gnomish.app.lease;

/**
 * The lease policy's injected monotonic time source: a single {@link #nanoTime()}
 * reading of a steadily-advancing, jump-free nanosecond counter used to measure how
 * long a claim version has stood unchanged (design D2). Injecting it lets a test
 * supply a controllable, deterministic value.
 *
 * <p>This is deliberately a <em>separate</em> seam from the engine's {@link
 * com.github.oinsio.gnomish.domain.engine.port.Clock}: that clock returns a
 * wall-clock {@link java.time.Instant}, which NTP can step forward or backward, and a
 * TTL measured on a wall clock could be shortened or lengthened by a clock
 * adjustment mid-window. Staleness is an <em>elapsed-duration</em> judgment on the
 * observer's own machine, so it must ride a monotonic counter ({@code
 * System.nanoTime}-based, per D2) that only ever advances — never a wall clock, and
 * never {@code updatedAt} from the tracker (D2 forbids cross-host clock arithmetic).
 * Kept to one method, mirroring the minimal {@code Clock}/{@code Sleeper} engine
 * seams.
 *
 * <p>Supports FR2, D2, NFR-R1 of add-claim-heartbeat.
 */
public interface MonotonicTime {

    /**
     * Returns the current value of the monotonic nanosecond counter. Only
     * <em>differences</em> between two readings are meaningful — the absolute value
     * has no fixed origin and may be negative, exactly like {@link System#nanoTime()}.
     * Successive readings are non-decreasing, so a subtraction of an earlier reading
     * from a later one yields a non-negative elapsed nanosecond count.
     *
     * <p>Supports FR2, D2, NFR-R1 of add-claim-heartbeat.
     *
     * @return the current monotonic counter reading, in nanoseconds
     */
    long nanoTime();
}
