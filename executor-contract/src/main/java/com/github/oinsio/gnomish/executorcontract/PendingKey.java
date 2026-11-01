package com.github.oinsio.gnomish.executorcontract;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The opaque key an executor returns with status {@code pending}, which the factory polls with. The
 * factory never interprets it; it only refuses a blank one. Wire shape: a non-empty string.
 *
 * <p>Implements FR2 of define-executor-contract.
 */
public record PendingKey(@JsonValue String value) {

    /** Refuses a blank key. */
    public PendingKey {
        WireChecks.nonBlank(value, "pendingKey");
    }

    @Override
    public String toString() {
        return value;
    }
}
