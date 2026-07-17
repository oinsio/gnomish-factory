package com.github.oinsio.gnomish.app;

import java.io.Serial;

/**
 * Signals that {@link RunnerOutcomeLoop#handleAborted} finished reporting a broken
 * durability guarantee: the cause and unpersisted-state summary are already printed to
 * {@link System#err} by the time this is thrown. Without this exception, {@code
 * Aborted} was indistinguishable from {@code Completed} at the CLI boundary — both
 * returned from the loop normally. {@link RunExitCodeMapper} maps this to the {@code
 * Aborted}-family exit code (12).
 *
 * <p>Implements FR12, D10 of add-manual-run.
 */
public final class AbortedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param cause the already-printed abort cause, carried for diagnostics
     */
    public AbortedException(String cause) {
        super(cause);
    }
}
