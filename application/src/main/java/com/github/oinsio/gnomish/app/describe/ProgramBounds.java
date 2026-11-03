package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.executorcontract.Bounds;
import java.time.Duration;
import java.util.Objects;

/**
 * The three time bounds the factory enforces on a {@code program} executor (NFR-R2 of
 * define-executor-contract; design D3, D5): {@code startup} — how long its {@code describe} may
 * take, the startup read before any round; {@code silence} — how long a run whose describe declares
 * {@code streams_progress} may go without a progress line; {@code overall} — how long one run may
 * take at all. The expiry of any is {@code failed/infrastructure} naming the bound; the request
 * carries all three as hints ({@link #hints()}), so a well-behaved executor can stop itself first.
 *
 * <p>The bounds are the factory's, not the executor's (an executor that set its own would set the
 * bound meant to contain it) and not the law's (no law field names them in this version). {@link
 * #DEFAULTS} is the one place the values are written; every consumer — the describe preflight and
 * the program executor — takes a {@code ProgramBounds} as a parameter from the composition root, so
 * an operator configuration key, when one is added, is one binding there.
 *
 * <p>Implements NFR-R2 of define-executor-contract.
 *
 * @param startup how long a describe may take; positive
 * @param silence how long a progress-streaming run may stay silent; positive
 * @param overall how long one run may take; positive
 */
public record ProgramBounds(Duration startup, Duration silence, Duration overall) {

    /** The factory's bounds: 30 seconds to describe, 5 minutes of silence, 30 minutes per run. */
    public static final ProgramBounds DEFAULTS =
            new ProgramBounds(Duration.ofSeconds(30), Duration.ofMinutes(5), Duration.ofMinutes(30));

    /** Refuses a missing or non-positive bound. */
    public ProgramBounds {
        requirePositive(startup, "startup");
        requirePositive(silence, "silence");
        requirePositive(overall, "overall");
    }

    private static void requirePositive(Duration bound, String name) {
        Objects.requireNonNull(bound, name);
        if (bound.isNegative() || bound.isZero()) {
            throw new IllegalArgumentException("the " + name + " bound must be positive, was " + bound);
        }
    }

    /**
     * The three bounds as the request's hints, in milliseconds; no turn, token or money bound (the law
     * declares none in this version, proposal NG12).
     *
     * @return the hints; never null
     */
    public Bounds hints() {
        return new Bounds(null, null, null, startup.toMillis(), silence.toMillis(), overall.toMillis());
    }
}
