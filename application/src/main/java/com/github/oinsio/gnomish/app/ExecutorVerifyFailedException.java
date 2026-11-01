package com.github.oinsio.gnomish.app;

import java.io.Serial;

/**
 * Ends a {@code gnomish executor verify} run in which at least one conformance case failed (FR12,
 * UX2 of define-executor-contract): the report, one line per case, is already on stdout, so {@link
 * RunExceptionReporting} prints nothing more for it, and {@link RunExitCodeMapper} maps it to exit
 * code 1. A run whose every case passed or did not apply throws nothing and exits 0.
 *
 * <p>Implements FR12, UX2 of define-executor-contract.
 */
public final class ExecutorVerifyFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** @param failed how many cases failed; at least one */
    public ExecutorVerifyFailedException(long failed) {
        super("the candidate failed " + failed + (failed == 1 ? " conformance case" : " conformance cases"));
    }
}
