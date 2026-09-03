package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.status.DaemonComponent;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standing reaper thread (design D1): unlike the old beat-riding reaper, this duty owns its own
 * virtual thread and ticks for the whole run's lifetime, whatever the held-claim count — including
 * zero, the {@code serve}-daemon-idle case this change fixes (FR1). Each tick reads a fresh
 * live-claims snapshot (design D3, typically {@code InstanceHeartbeat::liveClaimsSnapshot}) and
 * delegates to the real {@link ReaperDuty}.
 *
 * <p><b>Un-killable loop (design D4, FR3).</b> {@link #loop()} wraps both the interval sleep and the
 * tick in one {@code catch (Throwable)} — wider than {@link InstanceHeartbeat}'s {@code catch
 * (RuntimeException)} and covering the sleep too — so an {@code Error} or a throwing sleeper is
 * logged WARN and the loop continues. Only {@link #stop()} breaks it, without a WARN/ERROR or respawn.
 *
 * <p><b>Supervised restart (design D4/D5, FR4, NFR-O1, UX2).</b> The worker's {@code
 * uncaughtExceptionHandler} — the second rung behind the in-loop guard — respawns unless {@link
 * #stop()} already raced it, after an exponential backoff ({@link RestartBackoff}) on the same
 * injected {@link Sleeper}. Restarts are unbounded (the daemon is never killed on reaper failure);
 * each respawn logs an ERROR with a rising restart count, the only surface for a persistent fault.
 *
 * <p>Implements FR1, FR2, FR3, FR4 of fix-reaper-idle-liveness.
 */
public final class StandingReaper {

    private static final Logger log = LoggerFactory.getLogger(StandingReaper.class);

    private final ReaperDuty reaperDuty;
    private final Sleeper sleeper;
    private final Duration interval;
    private final Supplier<Collection<TaskRef>> liveClaimsSnapshot;
    private final Clock clock;

    private final Object lock = new Object();
    private final RestartBackoff restartBackoff = new RestartBackoff();
    private volatile boolean stopping;
    private @Nullable Thread worker;
    private volatile Instant lastRunAt;

    /**
     * @param reaperDuty the duty run every tick; never null
     * @param sleeper the interval sleeper (virtual under test); never null
     * @param interval the tick interval; never null
     * @param liveClaimsSnapshot supplies, fresh on every tick, the claims this instance holds live,
     *     excluded from staleness observation (design D3); never null
     * @param clock the source of the {@code lastRunAt} instant stamped after every completed tick
     *     (task 2.5, FR7 of add-serve-observability); never null
     */
    public StandingReaper(
            ReaperDuty reaperDuty,
            Sleeper sleeper,
            Duration interval,
            Supplier<Collection<TaskRef>> liveClaimsSnapshot,
            Clock clock) {
        this.reaperDuty = reaperDuty;
        this.sleeper = sleeper;
        this.interval = interval;
        this.liveClaimsSnapshot = liveClaimsSnapshot;
        this.clock = clock;
        this.lastRunAt = clock.now();
    }

    /**
     * Starts the worker virtual thread running {@link #loop()} (FR1). Idempotent: a second call
     * while a worker already runs is a no-op, so a double-start can never leak a second worker.
     */
    public void start() {
        synchronized (lock) {
            if (worker != null) {
                return;
            }
            worker = spawnWorker();
        }
    }

    /**
     * Stops the reaper: sets the {@code stopping} flag and interrupts the worker, so a sleep or
     * tick in flight unwinds into {@link #loop()}'s exit check rather than a respawn (FR4).
     */
    public void stop() {
        stopping = true;
        Thread current;
        synchronized (lock) {
            current = worker;
        }
        if (current != null) {
            current.interrupt();
        }
    }

    // Package-private: the resilience spec drives loop() on a real thread with a controllable
    // sleeper; the direct-tick spec never calls this.
    void loop() {
        while (!stopping) {
            try {
                sleeper.sleep(interval);
                tick();
                // A clean tick resets the backoff (design D5), so a later death backs off from base.
                restartBackoff.markCleanTick();
            } catch (Throwable e) {
                if (stopping) {
                    return;
                }
                log.warn(
                        OperatorEvent.STANDING_REAPER_TICK_FAILED.head()
                                + "standing reaper tick failed; thread continues",
                        e);
            }
        }
    }

    // Package-private: the direct-tick spec drives one reap synchronously, no thread involved.
    void tick() {
        reaperDuty.reapOnce(liveClaimsSnapshot.get());
        lastRunAt = clock.now();
    }

    // The worker's Thread.UncaughtExceptionHandler: fires only if something escapes loop()'s
    // Throwable guard. Respawns unless stop() already raced it (FR4), after an exponential backoff
    // on the same injected sleeper (design D5). The backoff sleep is itself guarded so it never
    // takes the daemon down; stop() may race the wait, in which case no respawn happens.
    private void onWorkerDeath(Thread dead, Throwable cause) {
        if (stopping) {
            return;
        }
        Duration backoff = restartBackoff.nextBackoff(interval);
        int restartCount = restartBackoff.nextRestartCount();
        log.error(
                OperatorEvent.STANDING_REAPER_WORKER_DIED.head()
                        + "standing reaper worker {} died; respawning after {} backoff (restart #{})",
                dead.getName(),
                backoff,
                restartCount,
                cause);
        try {
            sleeper.sleep(backoff);
        } catch (Throwable backoffFailure) {
            log.warn(
                    OperatorEvent.STANDING_REAPER_BACKOFF_SLEEP_FAILED.head()
                            + "backoff sleep before respawn failed; respawning without further delay",
                    backoffFailure);
        }
        if (stopping) {
            return;
        }
        synchronized (lock) {
            worker = spawnWorker();
        }
    }

    private Thread spawnWorker() {
        return Thread.ofVirtual()
                .name("gnomish-standing-reaper")
                .uncaughtExceptionHandler(this::onWorkerDeath)
                .start(DaemonComponent.REAPER.framing(this::loop));
    }

    @Nullable
    Thread worker() { // package-private: lifecycle specs read the current worker reference
        synchronized (lock) {
            return worker;
        }
    }

    /**
     * The last time a tick completed, or this reaper's construction instant if it has never ticked
     * (task 2.5). Implements FR7 of add-serve-observability.
     *
     * @return the last completed-tick instant; never null
     */
    public Instant lastRunAt() {
        return lastRunAt;
    }

    /**
     * How many times the supervisor has respawned this reaper after a death (task 2.5). Implements
     * FR7 of add-serve-observability.
     *
     * @return the lifetime restart count
     */
    public int restartCount() {
        return restartBackoff.restartCount();
    }

    /**
     * The reaper's tick cadence — the interval between completed runs absent a fault. Exposed into
     * {@code vitals.reaper.intervalSeconds} so a reader decides {@code lastRunAt} staleness against
     * the reaper's OWN cadence, not the faster snapshot-write cadence (design D10), keeping the
     * staleness rule computable from snapshot fields alone (M1). Implements FR7 of
     * add-serve-observability.
     *
     * @return the tick interval; never null
     */
    public Duration interval() {
        return interval;
    }
}
