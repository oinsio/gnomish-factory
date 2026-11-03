package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * A SARIF 2.1.0 result level, in rising order of severity; a check's threshold is one of them. A
 * result written without a level is a {@link #WARNING}, as SARIF defines. Unknown-token arm: an
 * unknown level makes the verdict a malformed answer, never a pass.
 *
 * <p>Implements FR3, NFR-R3 of define-executor-contract.
 */
public enum SarifLevel implements WireToken {
    /** No severity. */
    NONE("none"),
    /** A note. */
    NOTE("note"),
    /** A warning: the level of a result written without one. */
    WARNING("warning"),
    /** An error. */
    ERROR("error");

    private final String wire;

    SarifLevel(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The level {@code token} names, or empty for a token outside the set. */
    public static Optional<SarifLevel> fromWire(String token) {
        return WireToken.lookup(values(), token);
    }

    /**
     * The engine's pass derivation with this level as the threshold: the verdict passes when none
     * of its results reaches this level; the results below it are advisory findings.
     */
    public boolean passes(Answer.Verdict verdict) {
        for (SarifRun run : verdict.runs()) {
            for (SarifResult result : run.results()) {
                if (result.level().compareTo(this) >= 0) {
                    return false;
                }
            }
        }
        return true;
    }
}
