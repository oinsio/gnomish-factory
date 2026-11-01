package com.github.oinsio.gnomish.domain.engine;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * The component checks of {@link UsageSnapshot} and {@link UsageCost}, kept in a class of their own
 * so they stay in the mutation gate: PIT's record filter suppresses every mutation inside a record's
 * canonical constructor, and its Gregor engine crashes its minion on mutations of the static methods
 * of a record with a type-annotated component (hcoles/pitest#1285). Each check fails fast with the
 * component named.
 *
 * <p>Implements FR17 of define-executor-contract.
 */
final class UsageSnapshotChecks {

    private UsageSnapshotChecks() {}

    /** A snapshot cannot have folded fewer than no rounds. */
    static int requireNonNegativeRounds(int rounds) {
        if (rounds < 0) {
            throw new IllegalArgumentException("UsageSnapshot.rounds must not be negative");
        }
        return rounds;
    }

    /** A sum of non-negative costs cannot be negative. */
    static BigDecimal requireNonNegativePriced(BigDecimal priced) {
        if (priced.signum() < 0) {
            throw new IllegalArgumentException("UsageCost.priced must not be negative");
        }
        return priced;
    }

    /** A cost that names unpriced tokens is not complete. */
    static boolean requireIncompleteWhenUnpriced(
            boolean complete, Map<ExecutorRole, Map<String, TokenUsage>> unpriced) {
        if (complete && !unpriced.isEmpty()) {
            throw new IllegalArgumentException("UsageCost.complete must be false while tokens are unpriced");
        }
        return complete;
    }

    /**
     * A deep, unmodifiable copy of a role × model map, without the roles that name no model: a role
     * is present only with at least one model, so equal snapshots compare equal by content.
     */
    static Map<ExecutorRole, Map<String, TokenUsage>> copyOf(Map<ExecutorRole, Map<String, TokenUsage>> byRole) {
        var copy = new EnumMap<ExecutorRole, Map<String, TokenUsage>>(ExecutorRole.class);
        byRole.forEach((role, models) -> {
            if (!models.isEmpty()) {
                copy.put(role, Map.copyOf(models));
            }
        });
        return Map.copyOf(copy);
    }
}
