package com.github.oinsio.gnomish.app.serve;

/**
 * The daemon's own lifecycle state (FR4 of add-serve-observability): {@code running | draining |
 * stopping | stopped}. Mirrors {@code serveobservability.LifecycleState}'s four variants by name;
 * kept as a distinct, dependency-free type in this package — exactly like {@link FeedState} does
 * for {@code serveobservability.FeedPhase} — so {@code app.serve} never imports {@code
 * serveobservability} (module boundary, process-invariants.md). {@link
 * com.github.oinsio.gnomish.serveobservability.LifecycleSnapshotAssembler} maps a {@link
 * DaemonLifecycleView} onto the sealed {@code serveobservability.LifecycleState}.
 *
 * <p>Implements FR4 of add-serve-observability.
 */
public enum DaemonLifecycleState {

    /** The daemon is accepting and running tasks normally. */
    RUNNING,

    /** The daemon is finishing in-flight tasks and claiming no new ones. */
    DRAINING,

    /** The daemon is shutting down (e.g. SIGTERM received, cleanup in progress). */
    STOPPING,

    /** The daemon has exited; the terminal state, always paired with a reason. */
    STOPPED
}
