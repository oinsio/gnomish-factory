package com.github.oinsio.gnomish.app.serve;

/**
 * The "on any exit, kill the process tree" seam of the SIGTERM shutdown sequence (FR11, design
 * D9): {@link ServeShutdown} calls this as its final step regardless of how the grace-window wait
 * came out, so no gnome subprocess (agent-cli, git) survives the daemon. {@link
 * RealProcessTreeKiller} is the production implementation; a fake substitutes for it in tests so
 * {@link ServeShutdown}'s sequencing is provable without spawning or killing real OS processes.
 *
 * <p>Implements FR11, D9 of add-factory-serve.
 */
@FunctionalInterface
public interface ProcessTreeKiller {

    /** Destroys every descendant process of the current JVM (production: cooperative, then forcible). */
    void killDescendants();
}
