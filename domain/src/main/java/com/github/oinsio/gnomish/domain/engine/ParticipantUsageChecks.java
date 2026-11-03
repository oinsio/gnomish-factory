package com.github.oinsio.gnomish.domain.engine;

import java.math.BigDecimal;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * The component checks of {@link ParticipantUsage} and {@link ModelUsage}, kept in a class of their
 * own so they stay in the mutation gate: PIT's record filter suppresses every mutation inside a
 * record's canonical constructor, and its Gregor engine crashes its minion on mutations of the
 * static methods of a record with a type-annotated component (hcoles/pitest#1285). Each check fails
 * fast with the component named.
 *
 * <p>Implements FR15 of define-executor-contract.
 */
final class ParticipantUsageChecks {

    private ParticipantUsageChecks() {}

    /** A participant names the executor that ran it. */
    static String requireExecutor(String executor) {
        if (executor.isBlank()) {
            throw new IllegalArgumentException("ParticipantUsage.executor must not be blank");
        }
        return executor;
    }

    /** A {@code verdict} entry carries its non-negative vote index; no other role carries one. */
    static @Nullable Integer requireVoteOfRole(ExecutorRole role, @Nullable Integer vote) {
        if (role == ExecutorRole.VERDICT && vote == null) {
            throw new IllegalArgumentException("ParticipantUsage.vote is required on a verdict entry");
        }
        if (role != ExecutorRole.VERDICT && vote != null) {
            throw new IllegalArgumentException("ParticipantUsage.vote is carried by a verdict entry only");
        }
        if (vote != null && vote < 0) {
            throw new IllegalArgumentException("ParticipantUsage.vote must not be negative");
        }
        return vote;
    }

    /** An invocation cannot take negative wall time; {@code null} means unknown. */
    static @Nullable Duration requireNonNegativeWall(@Nullable Duration wallTime) {
        if (wallTime != null && wallTime.isNegative()) {
            throw new IllegalArgumentException("ParticipantUsage.wallTime must not be negative");
        }
        return wallTime;
    }

    /** A reported cost cannot be negative; {@code null} means unpriced, never zero. */
    static @Nullable BigDecimal requireNonNegativeCost(@Nullable BigDecimal costUsd) {
        if (costUsd != null && costUsd.signum() < 0) {
            throw new IllegalArgumentException("ModelUsage.costUsd must not be negative");
        }
        return costUsd;
    }
}
