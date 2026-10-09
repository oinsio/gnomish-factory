package com.github.oinsio.gnomish.app;

/**
 * The role a takeover prompt needs from the process's terminal: whether an operator is attached to
 * answer it (design D22 of supervise-daemon-loops-and-embed-dashboard, clause (iii): a seam a spec
 * fakes is a role interface in the owning module, never a JDK functional type). {@link
 * ConsoleTakeoverConfirmation} consults it before it builds a console, so a headless run never
 * touches standard input; the production answer is the JDK terminal probe, a spec answers directly.
 *
 * <p>Implements FR6 of add-claim-heartbeat; FR18 of supervise-daemon-loops-and-embed-dashboard (the
 * {@code BooleanSupplier} ban in {@code TimeSourceOwnerBoundarySpec}'s sibling scan).
 */
@FunctionalInterface
interface TerminalPresence {

    /**
     * Whether an interactive terminal is attached to this process right now.
     *
     * @return {@code true} only when an operator can answer a prompt
     */
    boolean attached();
}
