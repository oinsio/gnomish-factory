package com.github.oinsio.gnomish.domain.engine;

import com.github.oinsio.gnomish.domain.pipeline.Threshold;

/**
 * The SARIF 2.1.0 level of one result of a verdict answer, in rising order of severity — the
 * domain's own mirror of the executor contract's level, which {@link VerdictDerivation} judges
 * against a check's {@link Threshold} (design D5 of migrate-verify-to-executor-contract). A level
 * is always explicit here: the contract's reader already resolved a result written without one to
 * {@link #WARNING}, as SARIF defines.
 *
 * <p>This is a mirror rather than the contract's type itself: {@code :domain} may reach JDK-only
 * leafs only, and {@code :executor-contract} carries Jackson on its surface, so an adapter maps the
 * contract's level onto this one (the {@link ExecutionResult} precedent).
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.executorcontract.SarifLevel}: the same four
 * levels in the same rising order, each contract level with exactly one counterpart here (no shared
 * classpath, so the reference is by name).
 *
 * <p>Implements FR7 of migrate-verify-to-executor-contract.
 */
public enum ResultLevel {
    /** No severity: reaches no threshold. */
    NONE,
    /** A note. */
    NOTE,
    /** A warning. */
    WARNING,
    /** An error. */
    ERROR;

    /**
     * Whether a result at this level fails a check judged against {@code threshold}: it does when
     * its level is at or above the threshold's.
     *
     * @param threshold the check's threshold; never null
     * @return {@code true} when this level reaches the threshold
     */
    public boolean reaches(Threshold threshold) {
        return compareTo(lowestFailing(threshold)) >= 0;
    }

    private static ResultLevel lowestFailing(Threshold threshold) {
        return switch (threshold) {
            case ERROR -> ERROR;
            case WARNING -> WARNING;
            case NOTE -> NOTE;
        };
    }
}
