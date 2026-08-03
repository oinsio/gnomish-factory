package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;
import java.util.Collection;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standing reaper thread (design D1): unlike the old beat-riding reaper, this duty owns its
 * own virtual thread and ticks for the whole run's lifetime, whatever the instance's held-claim
 * count — including zero, the `serve`-daemon-idle case this change exists to fix (FR1). Each
 * tick reads a fresh live-claims snapshot (design D3, typically {@code
 * InstanceHeartbeat::liveClaimsSnapshot}) and delegates to the real {@link ReaperDuty}.
 *
 * <p><b>Un-killable loop (design D4, FR3).</b> {@link #loop()} wraps both the interval sleep and
 * the tick in one {@code catch (Throwable)} — wider than {@link InstanceHeartbeat}'s {@code catch
 * (RuntimeException)}, and covering the sleep call too — so an {@code Error} from an adapter or a
 * throwing sleeper is logged at WARN and the loop reaches its next sleep/tick round rather than
 * dying. Only an intentional {@link #stop()} (a {@code volatile stopping} flag plus an interrupt)
 * breaks the loop, and does so without a WARN/ERROR log or a respawn.
 *
 * <p><b>Supervision (design D4, FR4).</b> The worker thread carries an {@code
 * uncaughtExceptionHandler} that respawns a fresh worker unless {@link #stop()} was already
 * called — the second rung behind the in-loop guard, for the case nothing inside {@code
 * catch (Throwable)} can realistically reach.
 *
 * <p><b>Supervised restart (design D5, FR4, NFR-O1, UX2).</b> A respawn is never immediate: it
 * waits an exponential backoff (see {@link RestartBackoff}) on the same injected {@link Sleeper}
 * used for the tick interval, so the wait is virtualizable under test. Restarts are unbounded —
 * the daemon is never killed on reaper failure — and each respawn logs an ERROR line carrying a
 * monotonically increasing restart count, the only observability surface for a persistent fault.
 * A failure while backing off (including a throwing sleeper) is swallowed so it can never
 * propagate out of the uncaught-exception-handler machinery and crash the daemon.
 *
 * <p>Implements FR1, FR2, FR3, FR4 of fix-reaper-idle-liveness.
 */
public final class StandingReaper {

    private static final Logger log = LoggerFactory.getLogger(StandingReaper.class);

    private final ReaperDuty reaperDuty;
    private final Sleeper sleeper;
    private final Duration interval;
    private final Supplier<Collection<TaskRef>> liveClaimsSnapshot;

    private final Object lock = new Object();
    private final RestartBackoff restartBackoff = new RestartBackoff();
    private volatile boolean stopping;
    private @Nullable Thread worker;

    /**
     * @param reaperDuty the duty run every tick; never null
     * @param sleeper the interval sleeper (virtual under test); never null
     * @param interval the tick interval; never null
     * @param liveClaimsSnapshot supplies, fresh on every tick, the claims this instance currently
     *     holds live, excluded from staleness observation (design D3); never null
     */
    public StandingReaper(
            ReaperDuty reaperDuty,
            Sleeper sleeper,
            Duration interval,
            Supplier<Collection<TaskRef>> liveClaimsSnapshot) {
        this.reaperDuty = reaperDuty;
        this.sleeper = sleeper;
        this.interval = interval;
        this.liveClaimsSnapshot = liveClaimsSnapshot;
    }

    /**
     * Starts the worker virtual thread running {@link #loop()} (FR1). Idempotent: a second call
     * while a worker is already running is a no-op, so a stray double-start can never leak a second
     * ticking worker.
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
                // A full tick completed without dying: design D5's "clean run" resets the
                // backoff, so a later death starts backing off from the base interval again.
                restartBackoff.markCleanTick();
            } catch (Throwable e) {
                if (stopping) {
                    return;
                }
                log.warn("standing reaper tick failed; thread continues", e);
            }
        }
    }

    // Package-private: the direct-tick spec drives one reap synchronously, no thread involved.
    void tick() {
        reaperDuty.reapOnce(liveClaimsSnapshot.get());
    }

    // The worker's Thread.UncaughtExceptionHandler: fires only if something escapes even loop()'s
    // Throwable guard. Respawns a fresh worker unless stop() already raced it (FR4), after an
    // exponential backoff wait on the same injected sleeper (design D5) so the wait is
    // virtualizable under test. The backoff sleep itself is guarded: it must never propagate and
    // take the daemon down with it, and stop() may race the wait, in which case no respawn
    // happens.
    private void onWorkerDeath(Thread dead, Throwable cause) {
        if (stopping) {
            return;
        }
        Duration backoff = restartBackoff.nextBackoff(interval);
        int restartCount = restartBackoff.nextRestartCount();
        log.error(
                "standing reaper worker {} died; respawning after {} backoff (restart #{})",
                dead.getName(),
                backoff,
                restartCount,
                cause);
        try {
            sleeper.sleep(backoff);
        } catch (Throwable backoffFailure) {
            log.warn("backoff sleep before respawn failed; respawning without further delay", backoffFailure);
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
                .start(this::loop);
    }

    // Package-private: lifecycle specs read the current worker reference.
    @Nullable
    Thread worker() {
        synchronized (lock) {
            return worker;
        }
    }
}
