package com.github.oinsio.gnomish.serveobservability;

/**
 * The snapshot's {@code lifecycle.state}: {@link Running}, {@link Draining},
 * {@link Stopping}, or {@link Stopped} (FR4). Modeled as a sealed type rather
 * than an enum plus a nullable reason field so the compiler enforces that
 * only {@link Stopped} carries a reason — mirroring the {@code
 * status.Outcome} precedent (enum-like variants, one of which carries extra
 * data) rather than {@code EscalationDto}'s uniform-nullable-field shape,
 * since here only one variant ever has anything to say.
 *
 * <p>On graceful exit the daemon writes a final {@link Stopped} snapshot and
 * does not delete the file, so a stale {@link Running} snapshot (crash) is
 * distinguishable from a stale {@link Stopped} one (clean exit).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR4 of add-serve-observability.
 */
public sealed interface LifecycleState
        permits LifecycleState.Running, LifecycleState.Draining, LifecycleState.Stopping, LifecycleState.Stopped {

    /** The daemon is accepting and running tasks normally. */
    record Running() implements LifecycleState {}

    /** The daemon is finishing in-flight tasks and claiming no new ones. */
    record Draining() implements LifecycleState {}

    /** The daemon is shutting down (e.g. SIGTERM received, cleanup in progress). */
    record Stopping() implements LifecycleState {}

    /**
     * The daemon has exited; this is the final snapshot written for this
     * process (FR4).
     *
     * @param reason why the daemon stopped (e.g. {@code "sigterm"}, {@code
     *     "drainComplete"}); never blank
     */
    record Stopped(String reason) implements LifecycleState {}
}
