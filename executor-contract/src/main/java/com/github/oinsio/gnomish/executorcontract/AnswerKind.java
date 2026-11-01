package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * The kinds of an answer, the {@code kind} tokens of {@code executor-answer.schema.json}.
 * Unknown-token arm: an unknown kind is a malformed answer ({@link AnswerReading.Malformed}).
 *
 * <p>Implements FR3, NFR-R3 of define-executor-contract.
 */
enum AnswerKind implements WireToken {
    /** {@link Answer.Verdict}. */
    VERDICT("verdict"),
    /** {@link Answer.Select}. */
    SELECT("select"),
    /** {@link Answer.DecisionRequest}. */
    DECISION_REQUEST("decision-request");

    private final String wire;

    AnswerKind(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The kind {@code token} names, or empty for a token outside the set. */
    static Optional<AnswerKind> fromWire(String token) {
        return WireToken.lookup(values(), token);
    }
}
