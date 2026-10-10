package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.daemon.LoopOrder;
import com.github.oinsio.gnomish.app.daemon.LoopShape;
import com.github.oinsio.gnomish.app.daemon.LoopWait;
import com.github.oinsio.gnomish.app.daemon.RestartBackoff;
import com.github.oinsio.gnomish.app.daemon.RestartPolicy;
import com.github.oinsio.gnomish.app.daemon.SupervisedLoop;
import com.github.oinsio.gnomish.domain.engine.time.TimeEquipment;
import com.github.oinsio.gnomish.status.DaemonComponent;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * The standing reaper (design D1 of fix-reaper-idle-liveness): unlike the old beat-riding reaper,
 * this duty ticks for the whole run's lifetime, whatever the held-claim count — including zero,
 * the {@code serve}-daemon-idle case (FR1 of fix-reaper-idle-liveness). Each tick reads a fresh
 * live-claims snapshot (design D3 of that change, typically {@code
 * InstanceHeartbeat::liveClaimsSnapshot}) and delegates to the real {@link ReaperDuty}.
 *
 * <p><b>The thread is a supervised daemon loop</b> (design D1, D7 of
 * supervise-daemon-loops-and-embed-dashboard). This class owns only its tick and its vitals; the
 * thread, the guard, the stop and the restart belong to the {@link SupervisedLoop} it holds, shaped
 * wait → tick on a {@link LoopWait.FixedInterval} of the reaper's interval, framed as {@link
 * DaemonComponent#REAPER}, under {@link RestartPolicy.Unbounded} with the interval as the first
 * backoff and the shared {@link RestartBackoff#MAX_BACKOFF} cap — the policy the reaper always had, so a dead reaper is respawned
 * forever and its rising restart count stays the {@code vitals.reaper.restartCount} alarm. Its
 * failures log the loop's {@code DAEMON_LOOP_*} codes with {@code component=reaper}, rolled up on
 * this reaper's own clock once per six intervals (design D2).
 *
 * <p>A stop cuts short the interval wait but never a tick (design D4 of that change): a sweep
 * listing in flight completes within the tracker client's own deadline, and the loop ends at the
 * check after it.
 *
 * <p>Implements FR1, FR2, FR3, FR4 of fix-reaper-idle-liveness. Implements FR2, FR6 of
 * supervise-daemon-loops-and-embed-dashboard.
 */
public final class StandingReaper {

    private final ReaperDuty reaperDuty;
    private final Duration interval;
    private final LiveClaims liveClaims;
    private final InstantSource clock;
    private final SupervisedLoop loop;
    private volatile Instant lastRunAt;

    /**
     * @param reaperDuty the duty run every tick; never null
     * @param interval the tick interval, also the first restart backoff; never null
     * @param liveClaims answers, fresh on every tick, the claims this instance holds live,
     *     excluded from staleness observation (design D3); never null
     * @param time the time equipment (virtual under test): its sleeper waits the interval and the
     *     restart backoff; its clock stamps {@code lastRunAt} after every completed tick (task 2.5,
     *     FR7 of add-serve-observability) and times the loop's failure roll-ups (design D2, D20 of
     *     supervise-daemon-loops-and-embed-dashboard); never null
     */
    public StandingReaper(ReaperDuty reaperDuty, Duration interval, LiveClaims liveClaims, TimeEquipment time) {
        this.reaperDuty = reaperDuty;
        this.interval = interval;
        this.liveClaims = liveClaims;
        this.clock = time.clock();
        this.lastRunAt = clock.instant();
        LoopShape shape = new LoopShape(
                DaemonComponent.REAPER,
                LoopOrder.WAIT_THEN_TICK,
                new LoopWait.FixedInterval(time.sleeper(), interval),
                new RestartPolicy.Unbounded(interval));
        this.loop = new SupervisedLoop(shape, this::tick, time);
    }

    /** Starts the reaper's loop (FR1). Idempotent: a second call never starts a second thread. */
    public void start() {
        loop.start();
    }

    /**
     * Stops the reaper and returns at once: an interval wait in progress is cut short, a tick in
     * progress completes, and no respawn follows (FR4). Idempotent.
     */
    public void stop() {
        loop.stop();
    }

    // Package-private: lifecycle specs stop the loop and await its thread before they assert.
    void stopAndJoin() {
        loop.stopAndJoin();
    }

    // Package-private: the direct-tick specs drive one reap synchronously, no thread involved.
    void tick() {
        reaperDuty.reapOnce(liveClaims.snapshot());
        lastRunAt = clock.instant();
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
     * How many times this reaper's thread has been respawned after a death — the loop policy's
     * lifetime count (design D7 of supervise-daemon-loops-and-embed-dashboard). Implements FR7 of
     * add-serve-observability, FR6 of supervise-daemon-loops-and-embed-dashboard.
     *
     * @return the lifetime restart count
     */
    public int restartCount() {
        return loop.restartCount();
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
