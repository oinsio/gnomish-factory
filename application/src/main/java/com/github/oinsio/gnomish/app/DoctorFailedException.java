package com.github.oinsio.gnomish.app;

import java.io.Serial;

/**
 * Ends a {@code gnomish doctor} run in which at least one check failed (FR6 of add-doctor-command,
 * design D5): the report is already on stdout — the text lines or the {@code --json} document — so
 * {@link RunExceptionReporting} prints nothing more for it and logs nothing, and {@link
 * RunExitCodeMapper} maps it to exit code 1. A run with only {@code ok}, {@code warn} and {@code
 * skipped} results throws nothing and exits 0.
 *
 * <p>Implements FR6 of add-doctor-command.
 */
public final class DoctorFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** @param failed how many results of the run were {@code fail}; at least one */
    public DoctorFailedException(long failed) {
        super("doctor found " + failed + (failed == 1 ? " failing check" : " failing checks"));
    }
}
