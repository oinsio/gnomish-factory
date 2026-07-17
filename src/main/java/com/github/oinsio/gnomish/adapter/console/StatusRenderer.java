package com.github.oinsio.gnomish.adapter.console;

/**
 * The seam {@link DialogConsole} calls when it intercepts a {@code status} or
 * {@code status --json} meta-command (design D1): renders the current
 * {@code StatusReport} as text. The real implementation (task 6.x) wraps the
 * status-report builder and its text/JSON renders; kept as a one-method
 * interface here so {@code DialogConsole} has no upward dependency on the
 * status-reporting capability.
 *
 * <p>Implements FR10 of add-manual-run.
 */
public interface StatusRenderer {

    /**
     * Renders the current status.
     *
     * @param json when {@code true}, render as JSON; otherwise render as text
     * @return the rendered report, ready to print verbatim
     */
    String render(boolean json);
}
