package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.daemon.LoopOrder;
import com.github.oinsio.gnomish.app.daemon.LoopShape;
import com.github.oinsio.gnomish.app.daemon.LoopWait;
import com.github.oinsio.gnomish.app.daemon.RestartBackoff;
import com.github.oinsio.gnomish.app.daemon.RestartPolicy;
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop;
import com.github.oinsio.gnomish.app.lease.LivenessOracle;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.status.DaemonComponent;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * The daemon's sweep-lifecycle tick (design D7 of add-serve-sandbox-lifecycle): runs {@link
 * SandboxLifecyclePass} for the daemon's whole lifetime — one immediate startup tick, then every
 * {@code interval} thereafter — off the slot path (NFR-P1). A separate loop from the worktree
 * cleaner by design: container objects and host worktrees are disjoint populations with disjoint
 * cleaners (design D7, "no object has two cleaners").
 *
 * <p><b>The thread is a supervised daemon loop</b> (design D1, D7 of
 * supervise-daemon-loops-and-embed-dashboard). This class owns only its tick and its {@code
 * lastRunAt}; the thread, the guard, the stop and the restart belong to the {@link SupervisedLoop}
 * it holds: tick → wait on a {@link LoopWait.FixedInterval} of the configured interval, framed as
 * {@link DaemonComponent#SWEEP}, under {@link RestartPolicy.Unbounded} with the interval as the
 * first backoff, capped at {@link RestartBackoff#MAX_BACKOFF}. A failed tick, an {@code Error} included, is the loop's
 * {@code DAEMON_LOOP_TICK_FAILED} with {@code component=sweep}, and the next tick tries again.
 *
 * <p>Implements FR6, NFR-P1, NFR-R3 of add-serve-sandbox-lifecycle; FR6 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
public final class SandboxLifecycleTick {

    private final SandboxLifecyclePass pass;
    private final LivenessOracle livenessOracle;
    private final Path cloneDir;
    private final InstantSource clock;
    private final SupervisedLoop loop;
    private volatile Instant lastRunAt;

    /**
     * @param pass the sweep-lifecycle evaluation seam; never null
     * @param livenessOracle recomputed fresh every tick (task 2.1); never null
     * @param cloneDir the {@code --dir} project clone the project identity is resolved from
     * @param interval the tick cadence ({@code factory.serve.sandbox-sweep-interval}); never null
     * @param time the time equipment (virtual under test): its clock stamps {@code lastRunAt} after
     *     every completed tick and times the loop's failure roll-ups, its sleeper waits the tick
     *     interval and the restart backoff (design D2, D20 of
     *     supervise-daemon-loops-and-embed-dashboard); never null
     */
    public SandboxLifecycleTick(
            SandboxLifecyclePass pass,
            LivenessOracle livenessOracle,
            Path cloneDir,
            Duration interval,
            TimeEquipment time) {
        this.pass = pass;
        this.livenessOracle = livenessOracle;
        this.cloneDir = cloneDir;
        this.clock = time.clock();
        this.lastRunAt = clock.instant();
        LoopShape shape = new LoopShape(
                DaemonComponent.SWEEP,
                LoopOrder.TICK_THEN_WAIT,
                new LoopWait.FixedInterval(time.sleeper(), interval),
                new RestartPolicy.Unbounded(interval));
        this.loop = new SupervisedLoop(shape, this::tick, time);
    }

    /** Starts the sweep: one immediate tick, then every {@code interval} thereafter. Idempotent. */
    public void start() {
        loop.start();
    }

    /**
     * Stops the sweep and returns at once: an interval wait in progress is cut short, a tick in
     * progress completes, and no respawn follows (design D4). Idempotent.
     */
    public void stop() {
        loop.stop();
    }

    // Package-private: lifecycle specs stop the loop and await its thread before they assert.
    void stopAndJoin() {
        loop.stopAndJoin();
    }

    // Package-private: the policy spec drives this directly, with no thread and no real sleeping.
    void tick() {
        pass.run(cloneDir, livenessOracle.evaluate());
        lastRunAt = clock.instant();
    }

    /**
     * The last time a tick completed, or this tick's construction instant if it has never ticked
     * (task 6.1 vitals).
     *
     * @return the last completed-tick instant; never null
     */
    public Instant lastRunAt() {
        return lastRunAt;
    }
}
