package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.lease.LivenessOracle;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The daemon's sweep-lifecycle tick (design D7 of add-serve-sandbox-lifecycle): a virtual thread
 * running {@link SandboxLifecyclePass} for the daemon's whole lifetime — one immediate startup
 * tick, then every {@code interval} thereafter — off the slot path (NFR-P1), mirroring {@link
 * WorktreeJanitor}'s own "immediate-then-cadence, tick failure logged and retried" shape exactly,
 * per design D7's explicit call to reuse that pattern. A separate thread from the janitor by
 * design: container objects and host worktrees are disjoint populations with disjoint cleaners
 * (design D7, "no object has two cleaners").
 *
 * <p>Implements FR6, NFR-P1, NFR-R3 of add-serve-sandbox-lifecycle.
 */
public final class SandboxLifecycleTick {

    private static final Logger log = LoggerFactory.getLogger(SandboxLifecycleTick.class);

    private final SandboxLifecyclePass pass;
    private final LivenessOracle livenessOracle;
    private final Path cloneDir;
    private final Duration interval;
    private final Sleeper sleeper;
    private final Clock clock;
    private volatile Instant lastRunAt;

    /**
     * @param pass the sweep-lifecycle evaluation seam; never null
     * @param livenessOracle recomputed fresh every tick (task 2.1); never null
     * @param cloneDir the {@code --dir} project clone the project identity is resolved from
     * @param interval the tick cadence ({@code factory.serve.sandbox-sweep-interval}); never null
     * @param sleeper the tick-interval sleeper (virtual under test); never null
     * @param clock the source of the {@code lastRunAt} instant stamped after every completed tick
     */
    public SandboxLifecycleTick(
            SandboxLifecyclePass pass,
            LivenessOracle livenessOracle,
            Path cloneDir,
            Duration interval,
            Sleeper sleeper,
            Clock clock) {
        this.pass = pass;
        this.livenessOracle = livenessOracle;
        this.cloneDir = cloneDir;
        this.interval = interval;
        this.sleeper = sleeper;
        this.clock = clock;
        this.lastRunAt = clock.now();
    }

    /** Starts the tick thread: one immediate tick, then every {@code interval} thereafter. */
    public void start() {
        Thread.ofVirtual().name("gnomish-sandbox-lifecycle-tick").start(this::loop);
    }

    // Package-private: lifecycle specs drive this on their own thread with a controllable sleeper.
    void loop() {
        while (true) {
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn("sandbox lifecycle tick failed; will retry next tick", e);
            }
            sleeper.sleep(interval);
        }
    }

    // Package-private: the policy spec drives this directly, with no thread and no real sleeping.
    void tick() {
        pass.run(cloneDir, livenessOracle.evaluate());
        lastRunAt = clock.now();
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
