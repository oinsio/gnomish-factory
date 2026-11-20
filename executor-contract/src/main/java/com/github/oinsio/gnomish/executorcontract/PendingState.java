package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * What a {@code pending} result says about the subjects it awaits: the fact the engine classifies a
 * check's timeout by (design D19 of migrate-verify-to-executor-contract). At the timeout the engine
 * reads the last pending answer: {@link #UNREPORTED} or {@link #UNOBSERVED} resolve as cannot-verify,
 * since no verdict was obtained; {@link #RUNNING} or no state keeps the check's declared timeout
 * class. The names of what is awaited stay in the result's {@code reason}, shown and never decided
 * on. Unknown-token arm: an unknown token reads as an absent state, never as a failure.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.domain.engine.PendingState}: every contract
 * pending state has exactly one counterpart in the engine's own pending state, and an absent state
 * is the engine's {@code RUNNING}, which the adapter mapping a contract result into the engine's
 * must cover ({@code ProgramResultMapping}, design D8, D19 of migrate-verify-to-executor-contract).
 * {@code :domain} cannot reach this module, so the ends share no classpath.
 *
 * <p>Implements FR2, FR4, FR9 of migrate-verify-to-executor-contract.
 */
public enum PendingState implements WireToken {
    /** Every awaited subject was seen on the polled commit, and one is still running. */
    RUNNING("running"),
    /** An awaited subject never appeared on the polled commit. */
    UNREPORTED("unreported"),
    /** The source could not be read this time, such as for a rate limit. */
    UNOBSERVED("unobserved");

    private final String wire;

    PendingState(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The state {@code token} names, or empty for a token outside the set. */
    public static Optional<PendingState> fromWire(String token) {
        return WireToken.lookup(values(), token);
    }
}
