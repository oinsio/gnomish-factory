package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The instance-level heartbeat thread (design D3): ONE virtual thread per process that, on the
 * configured interval, beats EVERY {@code Working} claim the instance holds, writing a progress line
 * via {@link HeartbeatProgress}. Beating is the instance's duty, independent of the gnome — claim
 * liveness answers "is the holder process alive", never "is the work progressing" (FR1). The {@code
 * tracker.heartbeat} call and its beat-failure taxonomy (design D7, FR8) live in {@link
 * HeartbeatBeater}; the held-claim state machine and its lock live in {@link HeldClaims}.
 *
 * <p><b>Lifecycle.</b> The thread auto-starts on the FIRST {@link #register(TaskRef)} and stops
 * itself after any tick whose held set is empty; start and the empty-and-stop decision share one
 * lock (in {@link HeldClaims}), so a claim registered exactly as the thread stops is never lost. An
 * <i>abnormal</i> death (an {@code Error} or a throwing sleeper) is not resurrected — the designed
 * degradation (design D3): beats stop, the claim goes stale, a reaper returns it. {@link
 * #onWorkerDeath} only makes it loud (ERROR), clears {@code running} so a later {@link #register}
 * starts a fresh thread, and fires the {@link HeartbeatStateListener} so {@code died} reaches the
 * snapshot immediately (FR7). Each tick beats a lock-taken snapshot of this instance's own claims
 * (never held across a network write); a claim {@link HeartbeatBeater} reports gone is surfaced
 * through the {@link ClaimLostSink} and dropped without stopping the thread.
 *
 * <p>Implements FR1, FR8 of add-claim-heartbeat. Implements FR7 of add-serve-observability (the
 * {@link HeartbeatVitals} read-model and the {@link HeartbeatStateListener} state trigger).
 */
public final class InstanceHeartbeat implements ClaimBeat, HeartbeatVitals {

    private static final Logger log = LoggerFactory.getLogger(InstanceHeartbeat.class);

    private final HeartbeatBeater beater;
    private final Sleeper sleeper;
    private final Duration interval;
    private final ClaimLostSink claimLostSink;
    private final Clock clock;
    private final HeartbeatStateListener stateListener;
    private final HeldClaims claims = new HeldClaims();
    private volatile Instant lastTickAt;

    /**
     * Equivalent to the {@link HeartbeatStateListener}-taking constructor with {@link
     * HeartbeatStateListener#IGNORE} — every caller with no snapshot writer to wake, e.g. {@code take}.
     */
    public InstanceHeartbeat(
            Tracker tracker,
            HeartbeatProgress progress,
            Sleeper sleeper,
            Clock clock,
            Duration interval,
            ClaimLostSink claimLostSink) {
        this(tracker, progress, sleeper, clock, interval, claimLostSink, HeartbeatStateListener.IGNORE);
    }

    /**
     * Wires the collaborators the beat thread reads each tick.
     *
     * @param tracker the port the beat writes through; never null
     * @param progress the engine-event-fed progress source for the payload
     * @param sleeper the interval sleeper (virtual under test)
     * @param clock the source of the {@code alive-at} instant
     * @param interval the beat interval (design D8 default 5 min)
     * @param claimLostSink the seam a lost claim is surfaced through
     * @param stateListener woken after every {@link #state()} transition (FR7, design D4); {@link
     *     HeartbeatStateListener#IGNORE} absent a writer to wake
     */
    public InstanceHeartbeat(
            Tracker tracker,
            HeartbeatProgress progress,
            Sleeper sleeper,
            Clock clock,
            Duration interval,
            ClaimLostSink claimLostSink,
            HeartbeatStateListener stateListener) {
        this.beater = new HeartbeatBeater(tracker, progress, clock);
        this.sleeper = sleeper;
        this.interval = interval;
        this.claimLostSink = claimLostSink;
        this.clock = clock;
        this.stateListener = stateListener;
        this.lastTickAt = clock.now();
    }

    /**
     * Registers a newly claimed task and starts the beat thread on the first claim; idempotent for
     * an already-held ref. Implements FR1 of add-claim-heartbeat (design D3).
     *
     * @param ref the claimed task to begin beating; never null
     */
    @Override
    public void register(TaskRef ref) {
        // IDLE/DIED → RUNNING is the FR7 trigger; a register onto a running worker fires nothing.
        if (claims.registerAndMaybeStart(ref, this::loop, this::onWorkerDeath)) {
            notifyStateChanged();
        }
    }

    // The worker's UncaughtExceptionHandler; runs only on the abnormal exit (a normal loop() return
    // clears running without throwing). Not resurrected (design D3), only made loud and restart-safe.
    private void onWorkerDeath(Thread dead, Throwable e) {
        log.error("heartbeat thread {} died; held claims will go stale and be reaped", dead.getName(), e);
        claims.markDied();
        // RUNNING → DIED, the FR7 trigger: wakes the writer so `died` lands immediately (design D4).
        notifyStateChanged();
    }

    /**
     * Stops beating {@code ref}; the thread stops itself after the next tick that finds no claim
     * held. Called on release, terminal result, or claim loss. Implements FR1 of add-claim-heartbeat.
     *
     * @param ref the task to stop beating; never null
     */
    @Override
    public void unregister(TaskRef ref) {
        claims.remove(ref);
    }

    // Package-private so a spec drives the loop synchronously (seeded via seedHeldForTest, with a
    // sleeper that eventually throws a bound) rather than a background test that would hang.
    void loop() {
        while (true) {
            sleeper.sleep(interval);
            if (claims.stopIfEmpty()) {
                // RUNNING → IDLE: the normal empty-set stop is an FR7 transition too. Outside the lock.
                notifyStateChanged();
                return;
            }
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn("heartbeat tick failed; thread continues", e);
            }
        }
    }

    // Fires the FR7 state trigger (design D4). A listener that throws must never break beating or
    // the lifecycle (NFR-R1), so its failure is caught and logged. Always called outside the lock.
    private void notifyStateChanged() {
        try {
            stateListener.onStateChanged();
        } catch (RuntimeException e) {
            log.warn("heartbeat state listener failed; snapshot write may wait for the next timer beat", e);
        }
    }

    void seedHeldForTest(TaskRef ref) { // test seam: seed a claim without starting the worker
        claims.seed(ref);
    }

    // Package-private: the deterministic beat specs drive one tick directly.
    void tick() {
        lastTickAt = clock.now();
        for (TaskRef ref : claims.snapshot()) {
            if (beater.beat(ref)) {
                claimLostSink.claimLost(ref);
                unregister(ref);
            }
        }
    }

    @Nullable
    Thread worker() { // package-private: lifecycle specs join the worker to observe a stop
        return claims.worker();
    }

    /**
     * A snapshot of the claims this instance is actively beating right now (design D3), read by a
     * {@code StandingReaper} to exclude them from staleness checks; empty once the worker is not
     * running — a dead heartbeat's stale claims must NOT read as live. Implements FR2 of
     * fix-reaper-idle-liveness.
     *
     * @return the currently live claims; never null, empty when not running
     */
    public Set<TaskRef> liveClaimsSnapshot() {
        return claims.liveSnapshot();
    }

    @Override
    public HeartbeatWorkerState state() {
        return claims.state();
    }

    @Override
    public Instant lastTickAt() {
        return lastTickAt;
    }

    @Override
    public int heldClaims() {
        return claims.count();
    }
}
