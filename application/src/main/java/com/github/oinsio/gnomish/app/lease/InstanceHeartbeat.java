package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.logtext.RepeatSuppressor;
import com.github.oinsio.gnomish.logtext.ShutdownPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
 * #onWorkerDeath} only makes it loud — ERROR normally, WARN without a stack once {@link
 * ShutdownPhase} says the stop caused it (FR9 of harden-logging-observability) — clears {@code
 * running} so a later {@link #register}
 * starts a fresh thread, and fires the {@link HeartbeatStateListener} so {@code died} reaches the
 * snapshot immediately (FR7). Each tick beats a lock-taken snapshot of this instance's own claims
 * (never held across a network write); a claim {@link HeartbeatBeater} reports gone is surfaced
 * through the {@link ClaimLostSink} and dropped without stopping the thread.
 *
 * <p><b>Self-fencing (FR13 of harden-task-branch-contract).</b> Each held claim carries the instant
 * of its last <i>confirmed</i> beat. When that instant falls further behind than the lost-detection
 * threshold, the claim is surfaced as unconfirmed through the same {@link ClaimLostSink}, and the
 * run freezes its writes at the next boundary until it re-verifies. The threshold is strictly
 * earlier than the reaper's reassignment threshold, so a holder stops writing before any other
 * instance can be handed the task. A beat that lands confirms the claim again and lifts the freeze;
 * the thread keeps beating throughout, since an outage that ends is not a lost claim.
 *
 * <p>Implements FR1, FR8 of add-claim-heartbeat. Implements FR7 of add-serve-observability (the
 * {@link HeartbeatVitals} read-model and the {@link HeartbeatStateListener} state trigger).
 * Implements FR13 of harden-task-branch-contract.
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
    private final Duration lostDetection;
    private final Map<TaskRef, Instant> lastConfirmedAt = new ConcurrentHashMap<>();

    /** The tick streak's edge logging; the per-claim streaks belong to {@link HeartbeatBeater}. */
    private final HeartbeatTickLog tickLog;

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
     * Equivalent to the {@link HeartbeatStateListener}-taking constructor with the lost-detection
     * threshold defaulted to {@code interval} — the shape every caller that predates self-fencing
     * keeps. Production wiring supplies the threshold explicitly, derived from the same config the
     * reaper's TTL is (see {@code TakeHeartbeat}), so the two stay in their required order.
     */
    public InstanceHeartbeat(
            Tracker tracker,
            HeartbeatProgress progress,
            Sleeper sleeper,
            Clock clock,
            Duration interval,
            ClaimLostSink claimLostSink,
            HeartbeatStateListener stateListener) {
        this(tracker, progress, sleeper, clock, interval, claimLostSink, stateListener, interval);
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
     * @param lostDetection how far a claim's last confirmed beat may fall behind before the holder
     *     stops writing at its next boundary (FR13); strictly shorter than the reaper's
     *     reassignment threshold, which is what leaves the holder a grace window to recover in
     */
    public InstanceHeartbeat(
            Tracker tracker,
            HeartbeatProgress progress,
            Sleeper sleeper,
            Clock clock,
            Duration interval,
            ClaimLostSink claimLostSink,
            HeartbeatStateListener stateListener,
            Duration lostDetection) {
        this.lostDetection = lostDetection;
        // The edge-logging owner for the two streaks this thread can run: each claim's beat
        // failures (namespaced by HeartbeatBeater) and the tick itself failing. Built here rather
        // than injected because it is log-plane only — it decides how a repeated failure is
        // *said*, never what the beat does — and the constructor is already at the parameter
        // limit (process-invariants.md). FR4 of harden-logging-observability.
        RepeatSuppressor suppressor = new RepeatSuppressor(java.time.Clock.systemUTC(), rollUpFor(interval));
        this.tickLog = new HeartbeatTickLog(suppressor);
        this.beater = new HeartbeatBeater(tracker, progress, clock, suppressor);
        this.sleeper = sleeper;
        this.interval = interval;
        this.claimLostSink = claimLostSink;
        this.clock = clock;
        this.stateListener = stateListener;
        this.lastTickAt = clock.now();
    }

    /**
     * The quiet period between roll-ups for a loop that ticks every {@code interval}. A roll-up
     * period equal to the loop's own tick is no suppression at all — every repeat would qualify —
     * and the beat interval's own default (design D8) is exactly the suppressor's default, so the
     * period is derived from the loop rather than taken from the catalog: at most one reminder per
     * six beats, and never more often than {@link RepeatSuppressor#DEFAULT_ROLL_UP_INTERVAL}.
     */
    // Package-private so the derivation is asserted directly; the suppressor it feeds is built in
    // the constructor and never exposed.
    //
    // @DoNotMutate: provably equivalent mutant. The two arms of the comparison return equal
    //     durations at the boundary — when six beats are exactly the default, `>` and `>=` both
    //     yield the default's own value — so no covering test can distinguish the boundary
    //     mutation (testing.md, "provably equivalent mutant"). HeartbeatRollUpPeriodSpec covers
    //     the method on both sides of the boundary and on the boundary itself.
    @DoNotMutate
    static Duration rollUpFor(Duration interval) {
        Duration sixBeats = interval.multipliedBy(6);
        return sixBeats.compareTo(RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL) > 0
                ? sixBeats
                : RepeatSuppressor.DEFAULT_ROLL_UP_INTERVAL;
    }

    /**
     * Registers a newly claimed task and starts the beat thread on the first claim; idempotent for
     * an already-held ref. Implements FR1 of add-claim-heartbeat (design D3).
     *
     * @param ref the claimed task to begin beating; never null
     */
    @Override
    public void register(TaskRef ref) {
        // A tenure starts confirmed: the claim was just acquired, so its liveness is known now and
        // the lost-detection clock runs from here rather than from some earlier tenure's beat.
        lastConfirmedAt.put(ref, clock.now());
        // IDLE/DIED → RUNNING is the FR7 trigger; a register onto a running worker fires nothing.
        if (claims.registerAndMaybeStart(ref, this::loop, this::onWorkerDeath)) {
            notifyStateChanged();
        }
    }

    // The worker's UncaughtExceptionHandler; runs only on the abnormal exit (a normal loop() return
    // clears running without throwing). Not resurrected (design D3), only made loud and restart-safe.
    private void onWorkerDeath(Thread dead, Throwable e) {
        if (ShutdownPhase.inProgress()) {
            // FR9 of harden-logging-observability: the stop interrupted this worker on purpose, so
            // the held claims going stale is the designed outcome, not lost work.
            // throwable-not-subject: an interrupt's stack describes the stop, not a defect.
            log.warn(
                    OperatorEvent.HEARTBEAT_THREAD_STOPPED_BY_SHUTDOWN.head()
                            + "heartbeat thread {} stopped by the daemon shutdown ({}); held claims fall back to the lease TTL",
                    dead.getName(),
                    e.getClass().getSimpleName());
        } else {
            log.error(
                    OperatorEvent.HEARTBEAT_THREAD_DIED.head()
                            + "heartbeat thread {} died; held claims will go stale and be reaped",
                    dead.getName(),
                    e);
        }
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
        lastConfirmedAt.remove(ref);
        // A claim released mid-outage has no streak left to roll up, and leaving one behind would
        // grow the suppressor's map for the life of the process (FR4).
        beater.forget(ref);
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
            tickGuarded();
        }
    }

    /**
     * One tick under the loop's own guard: a collaborator bug must cost one tick, not the beat
     * thread (design D3). Package-private and separate from {@link #loop} so a spec drives both
     * edges — the failure and the recovery that closes it — synchronously, instead of racing the
     * worker thread for them.
     */
    void tickGuarded() {
        try {
            tick();
            tickLog.recovered();
        } catch (RuntimeException e) {
            tickLog.failed(e);
        }
    }

    // Fires the FR7 state trigger (design D4). A listener that throws must never break beating or
    // the lifecycle (NFR-R1), so its failure is caught and logged. Always called outside the lock.
    private void notifyStateChanged() {
        try {
            stateListener.onStateChanged();
        } catch (RuntimeException e) {
            log.warn(
                    OperatorEvent.HEARTBEAT_STATE_LISTENER_FAILED.head()
                            + "heartbeat state listener failed; snapshot write may wait for the next timer beat",
                    e);
        }
    }

    void seedHeldForTest(TaskRef ref) { // test seam: seed a claim without starting the worker
        claims.seed(ref);
    }

    // Package-private: the deterministic beat specs drive one tick directly.
    void tick() {
        Instant now = clock.now();
        lastTickAt = now;
        for (TaskRef ref : claims.snapshot()) {
            switch (beater.beat(ref)) {
                case CLAIM_GONE -> {
                    claimLostSink.claimLost(ref);
                    unregister(ref);
                }
                case BEATEN -> {
                    lastConfirmedAt.put(ref, now);
                    claimLostSink.claimConfirmed(ref);
                }
                case UNCONFIRMED -> fenceIfOverdue(ref, now);
            }
        }
    }

    /**
     * Surfaces {@code ref} as unconfirmed once its last confirmed beat is older than the
     * lost-detection threshold (FR13). Called on every unconfirmed beat rather than once, so the
     * signal survives a sink that was wired later, and because repeating it is idempotent.
     */
    private void fenceIfOverdue(TaskRef ref, Instant now) {
        Instant confirmed = lastConfirmedAt.get(ref);
        if (confirmed == null || Duration.between(confirmed, now).compareTo(lostDetection) < 0) {
            return;
        }
        log.warn(
                OperatorEvent.CLAIM_UNCONFIRMED_WRITES_FROZEN.head()
                        + "claim for {} unconfirmed for longer than {}; freezing writes until it is re-verified",
                ref.id(),
                lostDetection);
        claimLostSink.claimUnconfirmed(ref);
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
