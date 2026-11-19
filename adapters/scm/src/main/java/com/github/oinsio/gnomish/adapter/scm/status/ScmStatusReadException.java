package com.github.oinsio.gnomish.adapter.scm.status;

/**
 * An {@code scm-status} read that reached the platform and got no answer it could read a verdict
 * from: a persistent server error, a rejection no poll can cure (a {@code 401}, a {@code 404} of the
 * repository), or a body that is not the listing it asked for (FR4 of
 * migrate-verify-to-executor-contract). The executor answers it {@code failed/infrastructure}
 * naming the message; it never reads as a pass or as a context's absence.
 *
 * <p>Implements FR4 of migrate-verify-to-executor-contract.
 */
public final class ScmStatusReadException extends RuntimeException {

    ScmStatusReadException(String message) {
        super(message);
    }

    ScmStatusReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
