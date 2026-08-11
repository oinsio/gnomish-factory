package com.github.oinsio.gnomish.adapter.check;

/**
 * A {@link CheckEnvironmentSource} could not serve a check with an environment
 * — the workspace has the wrong shape for the source, or the fresh-box
 * materialization failed. The runner maps it to {@code Verdict.CannotVerify}
 * (an infrastructure failure): the verdict cannot be obtained, no stage
 * attempt is burned.
 *
 * <p>Implements FR13, NFR-R1 of add-sandbox-core.
 */
public class CheckEnvironmentUnavailableException extends RuntimeException {

    public CheckEnvironmentUnavailableException(String message) {
        super(message);
    }

    public CheckEnvironmentUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
