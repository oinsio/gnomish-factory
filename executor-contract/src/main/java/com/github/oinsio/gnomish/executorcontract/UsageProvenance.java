package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * Whether a result's usage was reported by the executor. Unknown-token arm: read as {@link #ABSENT},
 * never as zero usage.
 *
 * <p>Implements NFR-O1 of define-executor-contract.
 */
public enum UsageProvenance implements WireToken {
    /** The executor reported what the run consumed. */
    REPORTED("REPORTED"),
    /** The executor cannot report usage; never rendered as zero. */
    ABSENT("ABSENT");

    private final String wire;

    UsageProvenance(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The provenance {@code token} names, or empty for a token outside the set. */
    public static Optional<UsageProvenance> fromWire(String token) {
        return WireToken.lookup(values(), token);
    }

    @JsonCreator
    static UsageProvenance read(String token) {
        return fromWire(token).orElse(ABSENT);
    }
}
