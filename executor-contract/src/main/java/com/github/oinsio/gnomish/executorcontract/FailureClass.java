package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Optional;

/**
 * Why a run failed, deciding the attempt accounting. Unknown-token arm: read as {@link
 * #INFRASTRUCTURE} with a reason naming the token — the arm {@link ExecutorResult} applies.
 *
 * <p>Kept in sync with {@code com.github.oinsio.gnomish.domain.engine.ExecutionResult}: every contract
 * failure class has exactly one counterpart in the engine's own result shape ({@code Status}/{@code
 * FailureClass} there), which the adapter mapping a contract result into the engine's must cover.
 * {@code :domain} cannot reach this module, so the ends share no classpath.
 *
 * <p>Implements FR2, NFR-R3 of define-executor-contract.
 */
public enum FailureClass implements WireToken {
    /** The product is wrong: an attempt is burned and the findings are fed forward. */
    QUALITY("quality"),
    /** No outcome could be obtained: no attempt is burned; escalates after retries. */
    INFRASTRUCTURE("infrastructure"),
    /** A bound was reached: no attempt is burned; the stage ends with a budget-exhausted escalation. */
    LIMIT("limit");

    private final String wire;

    FailureClass(String wire) {
        this.wire = wire;
    }

    @Override
    @JsonValue
    public String wire() {
        return wire;
    }

    /** The class {@code token} names, or empty for a token outside the set. */
    public static Optional<FailureClass> fromWire(String token) {
        return WireToken.lookup(values(), token);
    }
}
