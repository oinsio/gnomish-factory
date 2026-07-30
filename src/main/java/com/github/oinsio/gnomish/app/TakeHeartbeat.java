package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.engine.SystemClock;
import com.github.oinsio.gnomish.app.lease.ClaimBeat;
import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.lease.HeartbeatProgress;
import com.github.oinsio.gnomish.app.lease.InstanceHeartbeat;
import com.github.oinsio.gnomish.app.lease.MonotonicTime;
import com.github.oinsio.gnomish.app.lease.Reaper;
import com.github.oinsio.gnomish.app.lease.StalenessMemory;
import com.github.oinsio.gnomish.app.lease.SystemMonotonicTime;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.domain.pipeline.TrackerConfig;
import java.time.Duration;

/**
 * Assembles, once per {@code take} invocation (task 6.1, design D3, D4), the instance heartbeat
 * machinery of the {@code app.lease} package and hands the take flow the two views it needs to
 * wire it in: the {@link ClaimBeat} lifecycle the claim choke point drives (register on the first
 * claim, unregister at the terminal result — see {@link TakeClaimAndWork#dispatchAfterClaim}) and
 * the {@link HeartbeatProgress} listener the engine run must fan events into so each beat carries
 * a live {@code stage}/{@code attempt} line (joined to the assembly's listener composite via
 * {@link ManualRunAssembly#withExtraListener}).
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
 */
record TakeHeartbeat(ClaimBeat instance, HeartbeatProgress progress, ClaimLossFlag flag) {

    /**
     * Builds the heartbeat machinery for one {@code take} run against {@code tracker}, reading the
     * beat interval and TTL multiplier from {@code config} (design D8). The claim staleness TTL is
     * {@code interval × multiplier}; the reaper and its per-run staleness memory ride the same beat
     * thread (design D4).
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
        Duration interval = config.heartbeatInterval();
        Duration ttl = interval.multipliedBy(config.heartbeatTtlMultiplier());
        var progress = new HeartbeatProgress();
        var flag = new ClaimLossFlag();
        var staleness = new StalenessMemory(monotonicTime, ttl);
        var reaper = new Reaper(tracker, staleness);
        var heartbeat = new InstanceHeartbeat(tracker, progress, sleeper, new SystemClock(), interval, flag, reaper);
        return new TakeHeartbeat(heartbeat, progress, flag);
    }
}
