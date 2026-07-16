package com.github.oinsio.gnomish.domain.engine;

import java.time.Duration;

/**
 * The outcome of running one verify check: which check ({@link CheckRef}), the
 * {@link Verdict} it reached, and the wall {@code duration} it took. This is the
 * per-check record the engine collects into a stage's verification result and
 * surfaces in telemetry and escalation reports (design D3).
 *
 * <p>A check cannot take negative time, so a negative {@code duration} is rejected;
 * {@link Duration#ZERO} is accepted since a check may complete in no measurable
 * time. Inert value data compared by content.
 *
 * <p>Implements FR4 of add-stage-engine.
 *
 * @param checkRef the identity of the check that ran
 * @param verdict the outcome the check reached
 * @param duration the wall time the check took; never negative
 */
public record CheckResult(CheckRef checkRef, Verdict verdict, Duration duration) {

    public CheckResult {
        duration = requireNonNegative(duration, "duration");
    }

    /**
     * Fails fast on a negative {@code duration}: a check cannot take negative wall
     * time (FR4). Kept as an explicit static method rather than inline in the
     * compact constructor: PIT's record filter suppresses all mutations inside a
     * record's canonical constructor, which would silently exempt this validation
     * from the 100% mutation gate.
     */
    private static Duration requireNonNegative(Duration value, String component) {
        if (value.isNegative()) {
            throw new IllegalArgumentException("CheckResult." + component + " must not be negative");
        }
        return value;
    }
}
