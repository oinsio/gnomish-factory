package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * An optional behaviour an executor declares in its describe, beyond {@code start}. Unknown-token
 * arm: an unknown capability is ignored.
 *
 * <p>Implements FR4 of define-executor-contract.
 */
public enum Capability implements WireToken {
    /** The executor answers {@code poll} for a pending key. */
    POLL("poll"),
    /** The executor can be cancelled. */
    CANCEL("cancel"),
    /** The executor writes progress lines while it runs, so the silence bound applies. */
    STREAMS_PROGRESS("streams_progress"),
    /** The executor can resume an interrupted run. */
    RESUMABLE("resumable");

    private final String wire;

    Capability(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The capability {@code token} names, or empty for a token outside the set. */
    public static Optional<Capability> fromWire(String token) {
        return WireToken.lookup(values(), token);
    }

    @JsonCreator
    static @Nullable Capability readOrNull(String token) {
        return fromWire(token).orElse(null);
    }
}
