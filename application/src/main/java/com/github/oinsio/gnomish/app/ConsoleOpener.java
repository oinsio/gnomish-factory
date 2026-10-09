package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.console.ConsoleIO;

/**
 * The role a takeover prompt needs to reach the operator console: open it, once a {@link
 * TerminalPresence} has said an operator is attached (design D22 of
 * supervise-daemon-loops-and-embed-dashboard, clause (iii): a seam a spec fakes is a role interface
 * in the owning module, never a JDK functional type — this replaces a {@code Supplier<ConsoleIO>}).
 * {@link ConsoleTakeoverConfirmation} calls it lazily, so a headless run never touches standard
 * input; the production answer wraps the process's own standard streams, a spec hands back a
 * recording console.
 *
 * <p>Implements FR6 of add-claim-heartbeat; FR18 of supervise-daemon-loops-and-embed-dashboard.
 */
@FunctionalInterface
interface ConsoleOpener {

    /**
     * Opens the operator console the prompt is printed to and the answer read from.
     *
     * @return the console; never null
     */
    ConsoleIO open();
}
