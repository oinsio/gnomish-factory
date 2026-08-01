package com.github.oinsio.gnomish;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Immutable typed configuration of the {@code serve} scheduler instance, bound from the {@code
 * factory.serve.*} external properties via constructor binding, mirroring {@link
 * FactoryProperties} (design D4). Kept as an independent top-level {@code @ConfigurationProperties}
 * record rather than nested inside {@link FactoryProperties} so that neither file needs to grow
 * past the {@code process-invariants.md} file-size target; Spring's
 * {@code @ConfigurationPropertiesScan} on {@link FactoryApplication} picks it up automatically —
 * no extra wiring needed.
 *
 * <p>These four knobs are deliberately few and all instance-level with CLI override (design D3,
 * D10): {@code --drain} itself is a per-invocation runtime flag on the future {@code serve}
 * command, not a persistent default, and is therefore not modeled here.
 *
 * <p>Implements FR1, FR5, FR11, FR14 of add-factory-serve.
 *
 * @param slots number of concurrent claim/work slots ({@code factory.serve.slots}); defaults to
 *     {@code 2} when unset (FR1, design D3); rejected if non-positive
 * @param idlePollInterval the single poll interval shared by both Idle-empty and Idle-blocked
 *     feed-automaton states ({@code factory.serve.idle-poll-interval}); defaults to {@code 30s}
 *     when unset (FR5, design D3); rejected if non-positive
 * @param sigtermGrace how long {@code SIGTERM} handling waits for in-flight slots before forcing
 *     shutdown ({@code factory.serve.sigterm-grace}); defaults to {@code 30s} when unset (FR11,
 *     design D3); rejected if non-positive
 * @param worktreeAgeThreshold minimum age of an unclaimed task worktree before the janitor
 *     disposes of it ({@code factory.serve.worktree-age-threshold}); defaults to {@code 14d} when
 *     unset (FR14, design D10); rejected if non-positive
 */
@ConfigurationProperties("factory.serve")
public record ServeProperties(
        int slots, Duration idlePollInterval, Duration sigtermGrace, Duration worktreeAgeThreshold) {

    private static final int DEFAULT_SLOTS = 2;
    private static final Duration DEFAULT_IDLE_POLL_INTERVAL = Duration.ofSeconds(30);
    private static final Duration DEFAULT_SIGTERM_GRACE = Duration.ofSeconds(30);
    private static final Duration DEFAULT_WORKTREE_AGE_THRESHOLD = Duration.ofDays(14);

    // slots is a primitive int, so it must match the record component type exactly to remain the
    // canonical constructor (unlike the Duration components, it cannot be @Nullable); Spring's
    // reflective binding supplies the primitive default 0 when the property is unset, which is
    // why 0 (not null) is the "unset" sentinel handled by defaultSlots.
    public ServeProperties(
            int slots,
            @Nullable Duration idlePollInterval,
            @Nullable Duration sigtermGrace,
            @Nullable Duration worktreeAgeThreshold) {
        this.slots = defaultSlots(slots);
        this.idlePollInterval = defaultIdlePollInterval(idlePollInterval);
        this.sigtermGrace = defaultSigtermGrace(sigtermGrace);
        this.worktreeAgeThreshold = defaultWorktreeAgeThreshold(worktreeAgeThreshold);
    }

    /**
     * Resolves the unset case to the design D3 default of 2 slots (FR1). Kept as an explicit
     * method rather than inline in the compact constructor: PIT's record filter suppresses all
     * mutations inside a record's canonical constructor, which would silently exempt the
     * validation logic from the mutation gate.
     */
    private static int defaultSlots(int slots) {
        if (slots < 0) {
            throw new IllegalArgumentException("factory.serve.slots must be positive");
        }
        return slots == 0 ? DEFAULT_SLOTS : slots;
    }

    /**
     * Resolves the unset case to the design D3 default of 30 seconds (FR5), shared by both
     * Idle-empty and Idle-blocked feed-automaton states. Kept as an explicit method for the same
     * PIT record-constructor reason as {@link #defaultSlots}.
     */
    private static Duration defaultIdlePollInterval(@Nullable Duration idlePollInterval) {
        if (idlePollInterval == null) {
            return DEFAULT_IDLE_POLL_INTERVAL;
        }
        if (idlePollInterval.isZero() || idlePollInterval.isNegative()) {
            throw new IllegalArgumentException("factory.serve.idle-poll-interval must be positive");
        }
        return idlePollInterval;
    }

    /**
     * Resolves the unset case to the design D3 default of 30 seconds (FR11). Kept as an explicit
     * method for the same PIT record-constructor reason as {@link #defaultSlots}.
     */
    private static Duration defaultSigtermGrace(@Nullable Duration sigtermGrace) {
        if (sigtermGrace == null) {
            return DEFAULT_SIGTERM_GRACE;
        }
        if (sigtermGrace.isZero() || sigtermGrace.isNegative()) {
            throw new IllegalArgumentException("factory.serve.sigterm-grace must be positive");
        }
        return sigtermGrace;
    }

    /**
     * Resolves the unset case to the design D10 default of 14 days (FR14). Kept as an explicit
     * method for the same PIT record-constructor reason as {@link #defaultSlots}.
     */
    private static Duration defaultWorktreeAgeThreshold(@Nullable Duration worktreeAgeThreshold) {
        if (worktreeAgeThreshold == null) {
            return DEFAULT_WORKTREE_AGE_THRESHOLD;
        }
        if (worktreeAgeThreshold.isZero() || worktreeAgeThreshold.isNegative()) {
            throw new IllegalArgumentException("factory.serve.worktree-age-threshold must be positive");
        }
        return worktreeAgeThreshold;
    }
}
