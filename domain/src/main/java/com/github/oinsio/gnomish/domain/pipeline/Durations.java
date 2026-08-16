package com.github.oinsio.gnomish.domain.pipeline;

import java.time.Duration;

/**
 * Small shared {@link Duration} predicate for the pure config-sanity rules in
 * this package ({@link StageSanityRule}, {@link TrackerConfigRule}): both flag
 * a non-positive duration (poll interval/timeout, resolved attempt-limit
 * adjacent heartbeat interval) as a located {@link ConfigError}, and both need
 * the same "strictly greater than zero" test to do it.
 */
final class Durations {

    private Durations() {}

    /**
     * True when {@code duration} is strictly greater than zero — neither zero
     * nor negative.
     */
    static boolean isPositive(Duration duration) {
        return !duration.isZero() && !duration.isNegative();
    }
}
