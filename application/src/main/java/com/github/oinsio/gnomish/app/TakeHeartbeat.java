package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.CachedOpenTaskListing;
import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.lease.HeartbeatProgress;
import com.github.oinsio.gnomish.app.lease.HeartbeatStateListener;
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat;
import com.github.oinsio.gnomish.app.lease.LivenessOracle;
import com.github.oinsio.gnomish.app.lease.MonotonicTime;
import com.github.oinsio.gnomish.app.lease.Reaper;
import com.github.oinsio.gnomish.app.lease.StalenessMemory;
import com.github.oinsio.gnomish.app.lease.StandingReaper;
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.domain.engine.time.SystemClock;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.time.Duration;

/**
 * Assembles, once per {@code take} invocation (task 6.1, design D3, D4), the instance heartbeat
 * machinery of the {@code app.lease} package and hands the take flow the two views it needs to
 * wire it in: the {@link ClaimBeat} lifecycle the claim choke point drives (register on the first
 * claim, unregister at the terminal result — see {@link TakeClaimAndWork#dispatchAfterClaim}) and
 * the {@link HeartbeatProgress} listener the engine run must fan events into so each beat carries
 * a live {@code stage}/{@code attempt} line (joined to the assembly's listener composite via
 * {@link RunAssembly#withExtraListener}).
 *
 * <p><b>What it constructs.</b> A single {@link HeartbeatProgress} (also the extra engine
 * listener); a {@link ClaimLossFlag} wired as the beat's lost-claim sink so a {@code ClaimGone}
 * answer is recorded AND retained here, so the take flow can thread it to the round-boundary
 * consult (task 6.3, FR8): the flag is now live, not write-only; a {@link Reaper} over a per-run
 * {@link StalenessMemory} on the production {@link SystemMonotonicTime}, with {@code ttl = interval
 * × multiplier} (design D8, task 5.1's derivation); and the {@link InstanceHeartbeat} tying them
 * together on the configured beat interval. The real-run beat interval sleeper is injected ({@code
 * ThreadSleeper} in production, a controllable sleeper under test); the {@code alive-at} clock is
 * the production {@link SystemClock}.
 *
 * <p>Implements FR1, FR4, FR8 of add-claim-heartbeat.
 *
 * @param instance the register/unregister lifecycle the claim choke point drives; never null
 * @param progress the engine-event listener whose snapshot each beat renders; never null
 * @param flag the claim-loss flag the beat sets on {@code ClaimGone} and the take flow consults at
 *     each round boundary (task 6.3, FR8); the SAME instance wired as the beat's sink; never null
 * @param standingReaper the standing reaper thread ticking on the same beat interval, independent
 *     of held-claim count (task 3.1, fix-reaper-idle-liveness FR5, design D2); never null
 * @param livenessOracle the tracked-object liveness oracle sharing the reaper's own listing and
 *     staleness memory (task 2.1 of add-serve-sandbox-lifecycle, NFR-C2); never null
 */
record TakeHeartbeat(
        ClaimBeat instance,
        HeartbeatProgress progress,
        ClaimLossFlag flag,
        StandingReaper standingReaper,
        LivenessOracle livenessOracle) {

    /**
     * Builds the heartbeat machinery for one {@code take} run against {@code tracker}, reading the
     * beat interval and TTL multiplier from {@code config} (design D8). The claim staleness TTL is
     * {@code interval × multiplier}; the reaper's per-run staleness memory is driven by the {@link
     * StandingReaper}, which ticks on its own thread independent of held-claim count (task 3.1,
     * fix-reaper-idle-liveness FR5, design D2).
     *
     * <p>Implements FR1, FR4, FR8 of add-claim-heartbeat.
     *
     * @param tracker the port the beat writes through and the reaper lists/removes claims with
     * @param config the resolved tracker config carrying the beat interval and TTL multiplier
     * @param sleeper the beat-interval sleeper — production {@code ThreadSleeper}, a controllable
     *     sleeper under test; never null
     * @return the assembled heartbeat views; never null
     */
    static TakeHeartbeat forRun(Tracker tracker, TrackerConfig config, Sleeper sleeper) {
        return forRun(tracker, config, sleeper, new SystemMonotonicTime());
    }

    /**
     * The serve overload (add-serve-observability FR1, FR7, design D4): identical to {@link
     * #forRun(Tracker, TrackerConfig, Sleeper)} but wires {@code stateListener} into the {@link
     * InstanceHeartbeat} so its {@link InstanceHeartbeat#state()} transitions — worker start,
     * abnormal death, idle stop — wake the snapshot writer immediately, landing {@code
     * vitals.heartbeat.state: died} without waiting for the timer beat. The {@code take} overloads
     * pass {@link HeartbeatStateListener#IGNORE}: no observability writer exists there to wake.
     *
     * <p>Implements FR1, FR4, FR8 of add-claim-heartbeat; FR1, FR7 of add-serve-observability.
     *
     * @param tracker the port the beat writes through and the reaper lists/removes claims with
     * @param config the resolved tracker config carrying the beat interval and TTL multiplier
     * @param sleeper the beat-interval sleeper; never null
     * @param stateListener woken after every heartbeat-state transition; never null
     * @return the assembled heartbeat views; never null
     */
    static TakeHeartbeat forRun(
            Tracker tracker, TrackerConfig config, Sleeper sleeper, HeartbeatStateListener stateListener) {
        return forRun(tracker, config, sleeper, sleeper, new SystemMonotonicTime(), stateListener);
    }

    /**
     * The {@link MonotonicTime}-injecting overload: identical to {@link #forRun(Tracker,
     * TrackerConfig, Sleeper)} but drives the reaper's {@link StalenessMemory} on the supplied
     * monotonic time source instead of the production {@link SystemMonotonicTime}, so a
     * controlled-clock integration test can step a held claim past its TTL deterministically (task
     * 6.6, M2). The default overload above supplies {@link SystemMonotonicTime}, so no production
     * call site changes — this mirrors the injectable-{@code sleeper} seam of task 6.1.
     *
     * <p>Implements FR1, FR4, FR8 of add-claim-heartbeat.
     *
     * @param tracker the port the beat writes through and the reaper lists/removes claims with
     * @param config the resolved tracker config carrying the beat interval and TTL multiplier
     * @param sleeper the beat-interval sleeper; never null
     * @param monotonicTime the monotonic time the reaper's staleness TTL is measured on — production
     *     {@link SystemMonotonicTime}, a controllable source under test; never null
     * @return the assembled heartbeat views; never null
     */
    static TakeHeartbeat forRun(Tracker tracker, TrackerConfig config, Sleeper sleeper, MonotonicTime monotonicTime) {
        return forRun(tracker, config, sleeper, sleeper, monotonicTime);
    }

    /**
     * The fully explicit overload (fix-reaper-idle-liveness FR5, design D2): identical to {@link
     * #forRun(Tracker, TrackerConfig, Sleeper, MonotonicTime)} but takes a SEPARATE interval sleeper
     * for the {@link StandingReaper}, independent of the beat's own sleeper. Production wiring is
     * unaffected — both overloads above simply pass the same {@code sleeper} for both roles, which is
     * harmless because the production {@code ThreadSleeper} is stateless and reentrant. The split
     * only matters to a test that drives the two threads' interval sleeps separately — e.g. a
     * rendezvous {@code BlockingSleeper} per thread, so releasing the beat's sleep can never be
     * mistaken for releasing the reaper's (and vice versa).
     *
     * <p>Implements FR1, FR4, FR8 of add-claim-heartbeat; FR5, NFR-S1 of fix-reaper-idle-liveness.
     *
     * @param tracker the port the beat writes through and the reaper lists/removes claims with
     * @param config the resolved tracker config carrying the beat interval and TTL multiplier — the
     *     sole source of the reaper's interval/TTL (NFR-S1 of fix-reaper-idle-liveness)
     * @param sleeper the beat-interval sleeper; never null
     * @param reaperSleeper the standing reaper's OWN interval sleeper, independent of {@code sleeper};
     *     never null
     * @param monotonicTime the monotonic time the reaper's staleness TTL is measured on — production
     *     {@link SystemMonotonicTime}, a controllable source under test; never null
     * @return the assembled heartbeat views; never null
     */
    static TakeHeartbeat forRun(
            Tracker tracker,
            TrackerConfig config,
            Sleeper sleeper,
            Sleeper reaperSleeper,
            MonotonicTime monotonicTime) {
        return forRun(tracker, config, sleeper, reaperSleeper, monotonicTime, HeartbeatStateListener.IGNORE);
    }

    /**
     * The full builder: {@link #forRun(Tracker, TrackerConfig, Sleeper, Sleeper, MonotonicTime)}
     * plus the {@link HeartbeatStateListener} threaded into the {@link InstanceHeartbeat} (FR1, FR7
     * of add-serve-observability). Every other overload funnels here, defaulting the listener to
     * {@link HeartbeatStateListener#IGNORE} except the serve overload above.
     *
     * <p>Implements FR1, FR4, FR8 of add-claim-heartbeat; FR5, NFR-S1 of fix-reaper-idle-liveness;
     * FR1, FR7 of add-serve-observability.
     *
     * @param tracker the port the beat writes through and the reaper lists/removes claims with
     * @param config the resolved tracker config carrying the beat interval and TTL multiplier
     * @param sleeper the beat-interval sleeper; never null
     * @param reaperSleeper the standing reaper's OWN interval sleeper, independent of {@code sleeper}
     * @param monotonicTime the monotonic time the reaper's staleness TTL is measured on; never null
     * @param stateListener woken after every heartbeat-state transition; never null
     * @return the assembled heartbeat views; never null
     */
    static TakeHeartbeat forRun(
            Tracker tracker,
            TrackerConfig config,
            Sleeper sleeper,
            Sleeper reaperSleeper,
            MonotonicTime monotonicTime,
            HeartbeatStateListener stateListener) {
        Duration interval = config.heartbeatInterval();
        Duration ttl = interval.multipliedBy(config.heartbeatTtlMultiplier());
        var progress = new HeartbeatProgress();
        var flag = new ClaimLossFlag();
        var staleness = new StalenessMemory(monotonicTime, ttl);
        var listing = new CachedOpenTaskListing();
        var reaper = new Reaper(tracker, staleness, listing);
        var heartbeat =
                new InstanceHeartbeat(tracker, progress, sleeper, new SystemClock(), interval, flag, stateListener);
        var standingReaper =
                new StandingReaper(reaper, reaperSleeper, interval, heartbeat::liveClaimsSnapshot, new SystemClock());
        var livenessOracle = new LivenessOracle(listing, staleness);
        return new TakeHeartbeat(heartbeat, progress, flag, standingReaper, livenessOracle);
    }
}
