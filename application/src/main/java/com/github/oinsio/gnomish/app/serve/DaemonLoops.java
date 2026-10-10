package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.lease.StandingReaper;

/**
 * The {@code serve} daemon's supervised daemon loops that live for the daemon's whole lifetime and
 * stop at shutdown as one group: the standing reaper, the worktree janitor and the sandbox sweep
 * tick (design D9 of supervise-daemon-loops-and-embed-dashboard). {@code ServeCommand} starts them
 * once, after the observability wiring has written its {@code started} line; {@link ServeShutdown}
 * stops them before its grace wait, so no disposal or sweep runs against a slot that is still
 * releasing its environment.
 *
 * <p>The snapshot writer is deliberately not a member: its stop is a final write, owned by {@code
 * ObservabilityWiring.finalizeStopped}, which runs after the drain (D9).
 *
 * <p>Every stop here is the non-blocking one (design D4): an interval wait in progress is cut
 * short, a tick in progress completes on its own, and nothing is joined — so {@link #stop()} holds
 * no lock and never waits. Each member's {@code start()} and {@code stop()} is idempotent, so
 * both methods here are too, and a second shutdown pass is a no-op.
 *
 * <p>Implements FR6, D9 of supervise-daemon-loops-and-embed-dashboard; FR4 of
 * fix-reaper-idle-liveness; FR14 of add-factory-serve.
 */
public final class DaemonLoops {

    private final StandingReaper reaper;
    private final WorktreeJanitor janitor;
    private final SandboxLifecycleTick sweep;

    /**
     * @param reaper the daemon-lifetime standing reaper (fix-reaper-idle-liveness FR1); never null
     * @param janitor the worktree janitor (FR14 of add-factory-serve); never null
     * @param sweep the sandbox sweep tick (FR6 of add-serve-sandbox-lifecycle); never null
     */
    public DaemonLoops(StandingReaper reaper, WorktreeJanitor janitor, SandboxLifecycleTick sweep) {
        this.reaper = reaper;
        this.janitor = janitor;
        this.sweep = sweep;
    }

    /** Starts all three loops, each on its own supervised thread. Idempotent. */
    public void start() {
        janitor.start();
        reaper.start();
        sweep.start();
    }

    /**
     * Stops all three loops and returns at once, without joining any of them (design D4, D9).
     * Idempotent.
     */
    public void stop() {
        janitor.stop();
        sweep.stop();
        reaper.stop();
    }
}
