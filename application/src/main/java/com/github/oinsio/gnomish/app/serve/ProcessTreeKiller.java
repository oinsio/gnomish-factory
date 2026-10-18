package com.github.oinsio.gnomish.app.serve;

/**
 * The shutdown step's seam (FR11, design D9 of add-factory-serve; design D19 of
 * fix-terminal-receipt-convergence, fourth amendment): "on any exit, kill the process tree and sweep
 * the groups no waiter has swept". {@link ServeShutdown} calls it as its final step regardless of how
 * the grace-window wait came out, and {@code OrderedExit.onSignal} calls it before the context closes
 * for every command that owns no hook, so no gnome subprocess (agent-cli, git, docker) survives the
 * factory. {@link RealProcessTreeKiller} is the production implementation; a fake substitutes for it
 * in tests so the sequencing is provable without spawning or killing real OS processes.
 *
 * <p>Implements FR11, D9 of add-factory-serve; FR21 of fix-terminal-receipt-convergence (D19, fourth
 * amendment).
 */
@FunctionalInterface
public interface ProcessTreeKiller {

    /**
     * Destroys every descendant process of the current JVM (production: cooperative, then forcible),
     * then sweeps every process group the factory started and no waiter has swept yet.
     */
    void killDescendants();
}
