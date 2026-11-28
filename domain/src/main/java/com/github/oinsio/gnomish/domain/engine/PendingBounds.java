package com.github.oinsio.gnomish.domain.engine;

import java.time.Duration;
import java.util.Objects;

/**
 * The factory's bounds on a pending {@code transform} round's wait (design D11 of
 * extract-agent-round-runner): {@code interval} — the least time between two polls, the pace being
 * {@code max(interval, pollAfter)}; {@code ceiling} — how long the round may stay pending at all,
 * counted from the {@code since} its pending record carries, so a restart never resets it. Expiry is
 * {@code failed/infrastructure} naming the ceiling, as every factory bound is (D3 of
 * define-executor-contract).
 *
 * <p>Factory-owned, beside the {@code program} executor's {@code ProgramBounds} in {@code
 * :application}, for the same reason D3 gives there: a bound set by the executor would be set by the
 * party it contains, and no law key sets it (human decision 2026-11-02). Unlike a check's interval,
 * no part of it travels on the request (NG13 of migrate-verify-to-executor-contract): the engine paces
 * the polls, the executor answers each one. {@link #DEFAULTS} is the one place the values are
 * written; the composition root hands them to the engine through {@code EnginePorts}, so an operator
 * key, when one is added, is one binding there.
 *
 * <p>Implements FR10, FR11 of extract-agent-round-runner.
 *
 * @param interval the least time between two polls of a pending round; positive
 * @param ceiling how long a round may stay pending, counted from its recorded start; positive
 */
public record PendingBounds(Duration interval, Duration ceiling) {

    /** The factory's bounds: a poll every 30 seconds at most, a pending round ends after 24 hours. */
    public static final PendingBounds DEFAULTS = new PendingBounds(Duration.ofSeconds(30), Duration.ofHours(24));

    /** Refuses a missing or non-positive bound. */
    public PendingBounds {
        requirePositive(interval, "interval");
        requirePositive(ceiling, "ceiling");
    }

    private static void requirePositive(Duration bound, String name) {
        Objects.requireNonNull(bound, name);
        if (bound.isNegative() || bound.isZero()) {
            throw new IllegalArgumentException("the pending " + name + " must be positive, was " + bound);
        }
    }
}
