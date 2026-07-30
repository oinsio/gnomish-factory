package com.github.oinsio.gnomish.app.lease;

import com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The instance-level heartbeat thread (design D3): ONE virtual thread per process that, on
 * the configured interval, beats EVERY {@code Working} claim the instance currently holds,
 * writing a human-readable progress line ({@code stage}, {@code attempt}, {@code alive-at})
 * derived from the engine event stream via {@link HeartbeatProgress}. Beating is the
 * instance's duty, independent of what a gnome or a slot thread does — a gnome blocked on
 * its executor for hours is still beaten, because claim liveness answers "is the holder
 * process alive", never "is the work progressing" (FR1).
 *
 * <p><b>Lifecycle.</b> The thread auto-starts on the FIRST {@link #register(TaskRef)} and
 * stops itself once no claim remains — after any tick whose held set is empty (a terminal
 * {@link #unregister(TaskRef)} or a lost claim). Start and the empty-and-stop decision share
 * one lock, so a claim registered exactly as the thread was stopping either keeps the
 * running thread alive or starts a fresh one — never a lost wakeup that leaves a held claim
 * unbeaten. Task 6.1 drives this lifecycle from the take run. An <i>abnormal</i> death (an
 * {@code Error} from deep in an adapter, or a throwing sleeper) is not resurrected — that is
 * the designed degradation (design D3 / Risks): beats stop, the claim goes stale, a reaper
 * returns the task, the fence neutralizes the zombie. {@link #onWorkerDeath} only makes the
 * death loud (ERROR) and clears {@code running} so a later {@link #register(TaskRef)} starts a
 * fresh thread rather than assume the dead one alive.
 *
 * <p><b>The tick.</b> Each interval it beats a snapshot of the held claims taken under the
 * lock (never holding the lock across a network write), then runs the {@link ReaperDuty}
 * once (design D4, the task-4.3 seam).
 *
 * <p><b>Beat-failure taxonomy (design D7, FR8).</b> Beat failures are classified by cause,
 * never counted toward a fuse. An <i>infrastructure</i> failure — a network/5xx exception
 * thrown by {@code tracker.heartbeat} — is caught, logged WARN, and the loop continues: the
 * thread NEVER dies, the remaining claims are still beaten this tick, and the failed one is
 * beaten again next tick, so beats resume on their own once the tracker recovers. A <i>claim
 * gone</i> answer ({@link HeartbeatResult.ClaimGone}: the marker was reaped or taken over) is a
 * lost claim: it is surfaced through the {@link ClaimLostSink} — a {@link ClaimLossFlag} in a
 * real run — and the dead claim is dropped from the held set, without stopping the whole thread
 * or beating the other claims any differently. The heartbeat only flags the loss; the reaction
 * (stop at the nearest round boundary exactly like a revocation: salvage, best-effort push, no
 * tracker writes) is the take run's, driven off the flag by task 6.1/6.3 — see {@link
 * ClaimLossFlag}.
 *
 * <p>Implements FR1, FR8 of add-claim-heartbeat.
 */
public final class InstanceHeartbeat implements ClaimBeat {

    private static final Logger log = LoggerFactory.getLogger(InstanceHeartbeat.class);

    private final Tracker tracker;
    private final HeartbeatProgress progress;
    private final Sleeper sleeper;
    private final Clock clock;
    private final Duration interval;
    private final ClaimLostSink claimLostSink;
    private final ReaperDuty reaper;

    private final Object lock = new Object();
    private final Set<TaskRef> held = new LinkedHashSet<>();
    private boolean running;
    private @Nullable Thread worker;

    /**
     * Wires the collaborators the beat thread reads each tick.
     *
     * @param tracker the port the beat writes through; never null
     * @param progress the engine-event-fed progress source for the payload; never null
     * @param sleeper the interval sleeper (virtual under test); never null
     * @param clock the source of the {@code alive-at} instant; never null
     * @param interval the beat interval (design D8 default 5 min); never null
     * @param claimLostSink the seam a lost claim is surfaced through (task 4.4); never null
     * @param reaper the per-tick reaper duty (task 4.3), {@link ReaperDuty#NONE} until then
     */
    public InstanceHeartbeat(
            Tracker tracker,
            HeartbeatProgress progress,
            Sleeper sleeper,
            Clock clock,
            Duration interval,
            ClaimLostSink claimLostSink,
            ReaperDuty reaper) {
        this.tracker = tracker;
        this.progress = progress;
        this.sleeper = sleeper;
        this.clock = clock;
        this.interval = interval;
        this.claimLostSink = claimLostSink;
        this.reaper = reaper;
    }

    /**
     * Registers a newly claimed task and starts the beat thread on the first claim (FR1,
     * D3). Idempotent for an already-held ref.
     *
     * <p>Implements FR1 of add-claim-heartbeat.
     *
     * @param ref the claimed task to begin beating; never null
     */
    @Override
    public void register(TaskRef ref) {
        synchronized (lock) {
            held.add(ref);
            if (!running) {
                running = true;
                worker = Thread.ofVirtual()
                        .name("gnomish-heartbeat")
                        .uncaughtExceptionHandler(this::onWorkerDeath)
                        .start(this::loop);
            }
        }
    }

    // The Thread.UncaughtExceptionHandler for the worker (a method reference, not a lambda, so no
    // synthetic wrapper carries an unmutatable cross-thread call). Runs only on the abnormal exit —
    // a normal loop() return clears running without throwing. The death is not resurrected (see the
    // Lifecycle javadoc / design D3), only made loud and restart-safe: clearing running under the
    // lock lets a later register() start a fresh thread.
    private void onWorkerDeath(Thread dead, Throwable e) {
        log.error("heartbeat thread {} died; held claims will go stale and be reaped", dead.getName(), e);
        synchronized (lock) {
            running = false;
        }
    }

    /**
     * Stops beating {@code ref}; the thread stops itself after the next tick that finds no
     * claim held (FR1). Called on release, terminal result, or claim loss.
     *
     * <p>Implements FR1 of add-claim-heartbeat.
     *
     * @param ref the task to stop beating; never null
     */
    @Override
    public void unregister(TaskRef ref) {
        synchronized (lock) {
            held.remove(ref);
        }
    }

    // Package-private (not private) so a spec can drive the whole loop synchronously on the test
    // thread — seeding the held set via {@link #seedHeldForTest} and supplying a sleeper that records
    // its intervals and eventually throws a bound (mirroring the synchronous ExternalPolling loop
    // spec). That makes the sleep call and the empty-and-stop branch killable by a deterministic
    // assertion (or a bounded runaway) rather than a background-thread test that would hang on the
    // mutant instead of failing.
    void loop() {
        while (true) {
            sleeper.sleep(interval);
            synchronized (lock) {
                if (held.isEmpty()) {
                    running = false;
                    return;
                }
            }
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn("heartbeat tick failed; thread continues", e);
            }
        }
    }

    // Test seam: seed the held set without starting the worker thread (which register() would), so a
    // spec can drive loop() synchronously on its own thread. Package-private, mirroring the existing
    // tick()/worker() seams the deterministic beat specs use.
    void seedHeldForTest(TaskRef ref) {
        synchronized (lock) {
            held.add(ref);
        }
    }

    // Package-private: the deterministic beat specs drive one tick directly.
    void tick() {
        List<TaskRef> refs;
        synchronized (lock) {
            refs = List.copyOf(held);
        }
        for (TaskRef ref : refs) {
            beat(ref);
        }
        // Hand the reaper the claims this instance holds so it excludes them from staleness
        // observation (design D13): an instance whose beats fail while listOpen still succeeds
        // must never reap its own live claim — only a foreign observer may.
        reaper.reapOnce(refs);
    }

    private void beat(TaskRef ref) {
        String payload = HeartbeatPayload.render(progress.progressFor(ref.id()), clock.now());
        HeartbeatResult result;
        try {
            result = tracker.heartbeat(ref, payload);
        } catch (RuntimeException e) {
            // Infrastructure failure (network/5xx), design D7: WARN and continue, no fuse burned.
            log.warn("beat failed for {}; continuing", ref.id(), e);
            return;
        }
        if (result instanceof HeartbeatResult.ClaimGone) {
            // Claim lost (design D7): flag it through the sink so the run reacts at its next
            // round boundary exactly like a revocation, and drop the dead claim from the tick.
            log.info("claim gone for {}; surfacing to sink and dropping", ref.id());
            claimLostSink.claimLost(ref);
            unregister(ref);
        }
    }

    // Package-private: lifecycle specs join the worker to observe a deterministic stop.
    @Nullable
    Thread worker() {
        synchronized (lock) {
            return worker;
        }
    }
}
