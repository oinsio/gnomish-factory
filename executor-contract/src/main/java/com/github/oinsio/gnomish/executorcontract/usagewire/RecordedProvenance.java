package com.github.oinsio.gnomish.executorcontract.usagewire;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * The provenance of a recorded participant entry, in the record documents' spelling: {@code
 * reported} or {@code absent} (status-report v1, "A usage object carries wall time, tools and tokens
 * by model"). An {@code absent} entry carries an empty model map and never renders as zero.
 *
 * <p>Unknown-token arm: a token outside the set reads as {@link #ABSENT} — unknown, never zero —
 * the arm the result file's provenance takes too.
 *
 * <p>Implements FR15, FR16, NFR-O1, NFR-R3 of define-executor-contract.
 */
public enum RecordedProvenance {
    /** The executor reported what the invocation consumed. */
    REPORTED("reported"),
    /** The executor reported nothing. */
    ABSENT("absent");

    private final String wire;

    RecordedProvenance(String wire) {
        this.wire = wire;
    }

    /** The token this constant is written as. */
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The provenance {@code token} names, or empty for a token outside the set. */
    public static Optional<RecordedProvenance> fromWire(String token) {
        for (RecordedProvenance constant : values()) {
            if (constant.wire.equals(token)) {
                return Optional.of(constant);
            }
        }
        return Optional.empty();
    }

    @JsonCreator
    static RecordedProvenance read(String token) {
        return fromWire(token).orElse(ABSENT);
    }
}
