package com.github.oinsio.gnomish.domain.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The pure tracker core-key rule (design D6): checks the semantics of the
 * {@code tracker} core keys the loader itself owns — the shared
 * {@code abort-threshold} (a <em>positive integer</em>, default 3) and the
 * heartbeat protocol constants {@code heartbeat-interval} (a positive duration,
 * default 5 minutes) and {@code heartbeat-ttl-multiplier} (an integer ≥ 3,
 * default 3; TTL = multiplier × interval, so the floor of 3 makes an
 * inconsistent beat/TTL pair inexpressible). A violation is reported as a
 * located {@link ConfigError} naming {@code config.yaml}, never thrown (design
 * D3) — mirroring how {@link StageSanityRule} flags a non-positive resolved
 * attempt limit or poll interval.
 *
 * <p>Also checks the shared {@code wip-limit} (an integer &ge; 1, default 10;
 * FR6, NFR-S3 of add-factory-serve) — a protocol constant read only from the
 * factory's own clone, alongside {@code abort-threshold}.
 *
 * <p>All applicable errors are aggregated into one list (FR8, honouring the
 * single-pass aggregation contract) in a fixed order — abort-threshold, then
 * wip-limit, then TTL multiplier, then interval — so an author sees every
 * core-key fault at once rather than one per load.
 *
 * <p>Default contract: {@link com.github.oinsio.gnomish.adapter.pipeline.PipelineMapper}
 * substitutes the defaults only when
 * a key is <em>omitted</em> (the wire value is {@code null}); an explicitly
 * declared value flows through to the {@link TrackerConfig} unchanged, so a
 * declared out-of-range value reaches this rule as-is and is flagged, while an
 * omitted one is already the valid default by the time it gets here.
 *
 * <p>Scope (no validation creep): the adapter-owned subsection is validated at
 * the seam by {@link com.github.oinsio.gnomish.adapter.pipeline.TrackerSeamValidator}
 * (task 3.2), not here; and an absent {@code tracker} section ({@code null})
 * yields no error — the section is optional (FR17).
 *
 * <p>Implements FR17 of add-tracker-port, FR3 of add-claim-heartbeat, FR6,
 * NFR-S3 of add-factory-serve.
 */
public final class TrackerConfigRule {

    private static final String FILE = "config.yaml";
    private static final String ABORT_THRESHOLD_WHERE = "tracker.abort-threshold";
    private static final String WIP_LIMIT_WHERE = "tracker.wip-limit";
    private static final String TTL_MULTIPLIER_WHERE = "tracker.heartbeat-ttl-multiplier";
    private static final String INTERVAL_WHERE = "tracker.heartbeat-interval";

    /** FR3 of add-claim-heartbeat, design D8: the TTL multiplier floor. */
    private static final int MIN_TTL_MULTIPLIER = 3;

    /** FR6 of add-factory-serve, design D3: the WIP limit floor. */
    private static final int MIN_WIP_LIMIT = 1;

    private TrackerConfigRule() {}

    /**
     * Validates the model's tracker core keys: an absent section, or a section
     * whose keys are all in range, yields no errors; each out-of-range core key
     * yields exactly one located {@link ConfigError}, all aggregated in
     * abort-threshold, wip-limit, TTL-multiplier, interval order.
     *
     * <p>Implements FR17 of add-tracker-port, FR3 of add-claim-heartbeat, FR6
     * of add-factory-serve.
     *
     * @param tracker the carried tracker core config, or {@code null} when
     *     {@code config.yaml} declares no {@code tracker} section
     */
    public static List<ConfigError> validate(@Nullable TrackerConfig tracker) {
        if (tracker == null) {
            return List.of();
        }
        List<ConfigError> errors = new ArrayList<>();
        int threshold = tracker.abortThreshold();
        if (threshold < 1) {
            errors.add(new ConfigError(
                    FILE,
                    ABORT_THRESHOLD_WHERE,
                    "non-positive abort-threshold %d; the threshold must be a positive integer".formatted(threshold)));
        }
        int wipLimit = tracker.wipLimit();
        if (wipLimit < MIN_WIP_LIMIT) {
            errors.add(new ConfigError(
                    FILE,
                    WIP_LIMIT_WHERE,
                    "non-positive wip-limit %d; the limit must be at least %d".formatted(wipLimit, MIN_WIP_LIMIT)));
        }
        int multiplier = tracker.heartbeatTtlMultiplier();
        if (multiplier < MIN_TTL_MULTIPLIER) {
            errors.add(new ConfigError(
                    FILE,
                    TTL_MULTIPLIER_WHERE,
                    "heartbeat-ttl-multiplier %d below the minimum of %d; the multiplier must be at least %d so TTL = multiplier × interval stays consistent"
                            .formatted(multiplier, MIN_TTL_MULTIPLIER, MIN_TTL_MULTIPLIER)));
        }
        Duration interval = tracker.heartbeatInterval();
        if (!isPositive(interval)) {
            errors.add(new ConfigError(
                    FILE,
                    INTERVAL_WHERE,
                    "non-positive heartbeat-interval %s; the interval must be positive".formatted(interval)));
        }
        return List.copyOf(errors);
    }

    private static boolean isPositive(Duration duration) {
        return !duration.isZero() && !duration.isNegative();
    }
}
