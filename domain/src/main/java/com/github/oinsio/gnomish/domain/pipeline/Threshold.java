package com.github.oinsio.gnomish.domain.pipeline;

import java.util.Optional;

/**
 * The lowest SARIF result level that fails a verify check under the engine's verdict derivation
 * (design D5 of migrate-verify-to-executor-contract): a result at or above it fails the check, one
 * below it stays an advisory finding. {@link #DEFAULT} is {@link #ERROR}.
 *
 * <p>Each constant has one wire token, the SARIF level name a manifest spells ({@link #wire()}),
 * and {@link #fromWire(String)} is its one reader, so the token set lives in one place and the
 * round trip is pinned by a spec over every constant (NFR-R3).
 *
 * <p>Implements FR7, NFR-R3 of migrate-verify-to-executor-contract.
 */
public enum Threshold {
    ERROR("error"),
    WARNING("warning"),
    NOTE("note");

    /** The threshold a check that declares none is judged against. */
    public static final Threshold DEFAULT = ERROR;

    private final String wire;

    Threshold(String wire) {
        this.wire = wire;
    }

    /** The token a manifest spells this threshold with. */
    public String wire() {
        return wire;
    }

    /**
     * The threshold {@code token} names, compared exactly (no case folding).
     *
     * @param token a manifest's {@code threshold} value; never null
     * @return the threshold, or empty when the token names none
     */
    public static Optional<Threshold> fromWire(String token) {
        for (Threshold threshold : values()) {
            if (threshold.wire.equals(token)) {
                return Optional.of(threshold);
            }
        }
        return Optional.empty();
    }
}
