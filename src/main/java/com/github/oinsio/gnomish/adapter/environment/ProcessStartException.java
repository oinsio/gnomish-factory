package com.github.oinsio.gnomish.adapter.environment;

/**
 * Thrown by {@link TaskExecutionEnvironment#exec(ExecCommand)} when the process
 * could not even be started — a misconfigured or missing binary, a container
 * runtime that is unavailable. Callers map it to their own failure class: an
 * agent round to an infrastructure failure of the round (NFR-R1), a command
 * check to {@code CannotVerify}.
 *
 * <p>Implements FR4, NFR-R1 of add-sandbox-core.
 */
public final class ProcessStartException extends RuntimeException {

    /**
     * @param message what failed to start; never null
     * @param cause the underlying failure (e.g. an {@code IOException}); never null
     */
    public ProcessStartException(String message, Throwable cause) {
        super(message, cause);
    }
}
