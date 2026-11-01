package com.github.oinsio.gnomish.executorcontract;

/**
 * The continuation of a run that asked a question: the continuation key its decision request
 * carried and the reply it received. Wire shape: {@code {"key", "reply"}}.
 *
 * <p>Implements FR1, FR3 of define-executor-contract.
 */
public record Continuation(String key, String reply) {

    /** Refuses a blank key. */
    public Continuation {
        WireChecks.nonBlank(key, "continuation.key");
    }
}
