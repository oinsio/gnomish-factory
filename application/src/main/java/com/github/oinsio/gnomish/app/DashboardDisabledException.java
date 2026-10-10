package com.github.oinsio.gnomish.app;

import java.io.Serial;

/**
 * Ends a standalone {@code gnomish dashboard --watch} whose render loop kept dying and was disabled
 * by its bounded restart policy (design D10 of supervise-daemon-loops-and-embed-dashboard): the
 * process has nothing else to do, so it exits with status 1 ({@link RunExitCodeMapper}) instead of
 * idling with a page nobody writes. The loop's own ERROR line ({@code DAEMON_LOOP_GAVE_UP}) is the
 * operator's report, so {@link RunExceptionReporting} prints nothing more for it.
 *
 * <p>Implements FR9 of supervise-daemon-loops-and-embed-dashboard.
 */
public final class DashboardDisabledException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** Creates the carrier; the loop has already logged why it was disabled. */
    public DashboardDisabledException() {
        super("dashboard watch loop disabled after repeated deaths");
    }
}
