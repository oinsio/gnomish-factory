package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * The closed status taxonomy of a result. Unknown-token arm: any other token is read as {@link
 * #FAILED} with {@link FailureClass#INFRASTRUCTURE} and a reason naming the token — the arm {@link
 * ExecutorResult} applies when it reads a result.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.domain.engine.ExecutionResult}: every contract
 * status has exactly one counterpart in the engine's own result shape ({@code Status}/{@code
 * FailureClass} there), which the adapter mapping a contract result into the engine's must cover.
 * {@code :domain} cannot reach this module, so the ends share no classpath.
 *
 * <p>Implements FR2, NFR-R3 of define-executor-contract.
 */
public enum Status implements WireToken {
    /** The run finished; an answer may ride along. */
    COMPLETED("completed"),
    /** The run failed, with a {@link FailureClass}. */
    FAILED("failed"),
    /** The run continues out of process; the factory polls with a {@link PendingKey}. */
    PENDING("pending");

    private final String wire;

    Status(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The status {@code token} names, or empty for a token outside the taxonomy. */
    public static Optional<Status> fromWire(String token) {
        return WireToken.lookup(values(), token);
    }
}
