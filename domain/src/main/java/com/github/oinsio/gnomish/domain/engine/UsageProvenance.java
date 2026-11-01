package com.github.oinsio.gnomish.domain.engine;

import java.util.Map;

/**
 * Whether an executor reported its usage (glossary "Usage provenance"; design D14 of
 * define-executor-contract): {@link #REPORTED}, or {@link #ABSENT} — the executor reported none, its
 * entry carries an empty model map, and it never renders as zero (NFR-O1).
 *
 * <p>Implements FR15, NFR-O1 of define-executor-contract.
 */
public enum UsageProvenance {
    /** The executor reported what the invocation consumed. */
    REPORTED,
    /** The executor reported nothing; unknown, never zero. */
    ABSENT;

    /**
     * The provenance of a carrier that has no word for it: {@code ABSENT} exactly when {@code
     * byModel} is empty — the agent CLI's rule.
     *
     * @param byModel the reported per-model usage; never null
     * @return the provenance the map implies; never null
     */
    public static UsageProvenance of(Map<String, ?> byModel) {
        return byModel.isEmpty() ? ABSENT : REPORTED;
    }

    /**
     * Refuses an {@code ABSENT} provenance beside a reported model: an executor that reported nothing
     * reported no model either.
     *
     * @param provenance the provenance; never null
     * @param byModel the per-model usage carried beside it; never null
     * @throws IllegalArgumentException when {@code provenance} is {@code ABSENT} and {@code byModel}
     *     is not empty
     */
    static void requireConsistent(UsageProvenance provenance, Map<String, ?> byModel) {
        if (provenance == ABSENT && !byModel.isEmpty()) {
            throw new IllegalArgumentException("an ABSENT usage carries no model, got " + byModel.keySet());
        }
    }
}
