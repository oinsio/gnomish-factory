package com.github.oinsio.gnomish.subprocess;

/**
 * What one {@link ProcessGroupSweep} found after an invocation's exit: how many rounds of {@code
 * SIGKILL} the group took, and whether the zero-signal probe was refused at the end (design D19 of
 * fix-terminal-receipt-convergence). Each round runs because the probe before it was answered, so
 * {@code rounds} is also the count of probes that found a survivor.
 *
 * <p>A value, not a log line: this module does not log (NG4), so the waiter that has a log plane
 * says one DEBUG line for a sweep that found a survivor, naming the count (NFR-O3); a sweep that
 * found the group already empty is the healthy case and owes nothing.
 *
 * <p>Implements FR21, NFR-O3 of fix-terminal-receipt-convergence (D19).
 *
 * @param rounds the rounds of {@code kill -KILL -- -<pid>} sent, at most {@link
 *     ProcessGroupSweep#MAX_ROUNDS}
 * @param cleared whether the last probe was refused, so no member of the group was left; {@code
 *     false} after the last round, or when a probe could not be sent at all
 */
public record GroupSweep(int rounds, boolean cleared) {

    /** The sweep of a group that was already empty, or of a process with no group of its own. */
    public static final GroupSweep NOTHING = new GroupSweep(0, true);

    /** Returns whether a member of the group was found alive after the exit: the waiter's DEBUG line. */
    public boolean foundSurvivors() {
        return rounds > 0 || !cleared;
    }

    /** Returns the sweep in the words of the waiter's DEBUG line: the rounds, and whether it cleared. */
    public String phrase() {
        return "process group swept after the exit: rounds=" + rounds + ", cleared=" + cleared;
    }
}
