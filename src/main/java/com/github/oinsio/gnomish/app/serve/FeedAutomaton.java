package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.BackoffPolicy;
import com.github.oinsio.gnomish.app.take.FeedPolicy;
import com.github.oinsio.gnomish.app.take.OpenFrontGate;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;

/**
 * The {@code serve} feed loop: the four-state automaton (design D1) that decides, cycle by
 * cycle, whether to claim, sleep, or wait — driving {@link SlotLedger}, {@link FeedPolicy}
 * (design D2), and {@link Tracker#claim} to do it. The poll-and-claim mechanics themselves live
 * in {@link FeedCycle}, shared verbatim by {@link #step()} and drain mode's {@link #drain()}.
 *
 * <p><b>Full</b> is the ledger's own block: every cycle starts with {@link
 * SlotLedger#acquire()} <em>before</em> any tracker call (design D1), so with no free slot the
 * thread simply blocks there — zero polls — until {@link SlotLedger#release} wakes it, no timer
 * involved. No separate polling thread is needed.
 *
 * <p><b>Filling</b> has no pause: once a permit is acquired, one poll ({@code listReady} +
 * {@code listOpen}) feeds {@link FeedPolicy#selectClaimCandidates}, and a non-empty list is
 * walked exactly like {@code TakeBareAuto} — a claim-race loss ({@link ClaimResult.Held}) or a
 * per-candidate {@link OpenFrontGate} rejection falls through to the next candidate. Whether a
 * claim succeeds or every candidate is raced away, the cycle reports {@link FeedState#FILLING}
 * and loops again with no sleep — an eligible task existed at poll time, which is what defines
 * Filling (FR5).
 *
 * <p><b>Idle</b> shares one jittered interval: an empty candidate list releases the permit
 * ({@link SlotLedger#abandon()}) and sleeps {@link #idlePollInterval} plus a uniform 0-20%
 * jitter (design D4) via the injected {@link Sleeper} — deterministic under test with a virtual
 * sleeper. The reported state distinguishes {@link FeedState#IDLE_EMPTY} (nothing survived the
 * backoff filter) from {@link FeedState#IDLE_BLOCKED} (backoff-eligible entries existed but were
 * all fresh and WIP-blocked), mirroring {@code TakeBareAuto}'s empty-candidates split.
 *
 * <p><b>Drain mode</b> (FR10, M3): {@link #drain()} runs the same {@link FeedCycle} as {@link
 * #step()}'s Filling path, but the first empty poll commits to stopping for good — no idle sleep,
 * no re-poll — then blocks on {@link SlotLedger#awaitDrained()} until every already-running slot
 * finishes. See {@link #drain()}'s own Javadoc for the closing-report hand-off.
 *
 * <p><b>Observability</b>: every feed-state transition is logged once via {@link
 * FeedStateLogger} — never on every cycle spent in the same state (NFR-O1, UX2).
 *
 * <p><b>Outage tolerance</b> (NFR-R3): {@link FeedCycle}'s tracker calls run through {@link
 * FeedOutageRetry}, so a sustained tracker outage during {@link #step()} or {@link #drain()} is
 * caught, logged WARN, and retried with backoff — the same jittered idle interval reused as the
 * outage pause — instead of propagating and killing the feed thread. See {@link FeedOutageRetry}'s
 * Javadoc for the design rationale.
 *
 * <p>Implements FR5, FR9, FR10, NFR-O1, NFR-O2, NFR-R3, M3, D1, D4 of add-factory-serve.
 */
public final class FeedAutomaton {

    /** Design D4: up to +20% jitter on the idle interval. */
    private static final double JITTER_MAX_FRACTION = 0.20;

    private final SlotLedger slotLedger;

    // Not read directly by this class (only FeedCycle dispatches to it) — kept as a field rather
    // than a constructor-local so ServeCommandSpec's ".@slotRunner" reflective field access (its
    // only way to reach TakeSlotRunner's private collaborators for FR13's wiring assertions)
    // keeps working.
    @SuppressWarnings("unused")
    private final SlotRunner slotRunner;

    private final Sleeper sleeper;
    private final Clock clock;
    private final Duration backoffBase;
    private final Duration backoffCap;
    private final Duration idlePollInterval;
    private final int wipLimit;
    private final Random random;

    /** Feed-state transition logging (NFR-O1), extracted to keep this class within size limits. */
    private final FeedStateLogger stateLogger = new FeedStateLogger();

    /** The shared poll-and-claim mechanics (extracted to keep this class within size limits). */
    private final FeedCycle cycle;

    /**
     * @param tracker the tracker port the feed polls and claims through; never null
     * @param instanceId this instance's identity, passed to {@link Tracker#claim}; never null
     * @param slotLedger the shared capacity primitive (design D1); never null
     * @param slotRunner the slot-body seam (task 4.3 supplies the real one); never null
     * @param sleeper the idle-interval sleeper (virtual under test); never null
     * @param clock supplies "now" for the backoff filter; never null
     * @param backoffBase the abort-backoff base (design D10 of add-tracker-port); never null
     * @param backoffCap the abort-backoff cap; never null
     * @param idlePollInterval the shared Idle-empty/Idle-blocked poll interval (FR5); positive
     * @param wipLimit the configured WIP limit W (FR6); positive
     * @param random randomness for the head-zone pick and idle jitter; seeded = deterministic
     */
    public FeedAutomaton(
            Tracker tracker,
            InstanceId instanceId,
            SlotLedger slotLedger,
            SlotRunner slotRunner,
            Sleeper sleeper,
            Clock clock,
            Duration backoffBase,
            Duration backoffCap,
            Duration idlePollInterval,
            int wipLimit,
            Random random) {
        this.slotLedger = slotLedger;
        this.slotRunner = slotRunner;
        this.sleeper = sleeper;
        this.clock = clock;
        this.backoffBase = backoffBase;
        this.backoffCap = backoffCap;
        this.idlePollInterval = idlePollInterval;
        this.wipLimit = wipLimit;
        this.random = random;
        // NFR-R3: the outage backoff reuses the Idle state's own jittered interval (see
        // jitteredIdleInterval()) rather than a separate policy — see FeedOutageRetry's Javadoc.
        var outageRetry = new FeedOutageRetry(sleeper, this::jitteredIdleInterval);
        this.cycle = new FeedCycle(
                tracker,
                instanceId,
                slotLedger,
                slotRunner,
                backoffBase,
                backoffCap,
                wipLimit,
                random,
                stateLogger,
                outageRetry);
    }

    /**
     * Runs the automaton until interrupted. Each iteration is exactly {@link #step()}; a caller
     * stops it by interrupting the calling thread, surfacing at the next {@link
     * SlotLedger#acquire()} block or {@link Sleeper#sleep} return as an {@link
     * InterruptedException}.
     */
    public void run() throws InterruptedException {
        while (true) {
            step();
        }
    }

    /**
     * Drain mode (FR10, M3): claims and runs eligible tasks exactly like {@link #step()}'s
     * Filling path via the shared {@link FeedCycle}, but the first empty poll commits to
     * stopping for good — no idle sleep, no re-poll (the plain "nothing eligible to claim"
     * one-shot stop signal, matching the "Nightly drain" scenario's bounded-run framing). Then
     * blocks on {@link SlotLedger#awaitDrained()} so every already-running slot still finishes.
     * An empty queue at the very first poll returns immediately (M3: "--drain on an empty queue
     * exits 0"). Outcome collection is not this method's job: {@code ServeCommand} attaches a
     * {@link DrainReport} to the real {@link SlotRunner} (see {@link
     * TakeSlotRunner#attachDrainReport}) before calling this, since only the slot body knows
     * each task's terminal outcome.
     *
     * <p>Implements FR10, NFR-O2, M3 of add-factory-serve.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    public void drain() throws InterruptedException {
        while (true) {
            slotLedger.acquire();
            FeedCycle.Poll poll = cycle.poll(clock.now());
            if (poll.candidates().isEmpty()) {
                slotLedger.abandon();
                break;
            }
            cycle.claimOrAbandon(poll.candidates());
        }
        slotLedger.awaitDrained();
    }

    /**
     * Runs exactly one feed cycle and reports the observed state. Package-private so specs drive
     * the automaton one cycle at a time, mirroring {@code InstanceHeartbeat.tick()}.
     */
    FeedState step() throws InterruptedException {
        slotLedger.acquire();
        FeedCycle.Poll poll = cycle.poll(clock.now());

        if (poll.candidates().isEmpty()) {
            slotLedger.abandon();
            FeedState idleState = idleState(poll.readyTasks(), poll.now());
            stateLogger.onTransition(idleState, poll.openFrontCount(), wipLimit);
            sleeper.sleep(jitteredIdleInterval());
            return idleState;
        }

        cycle.claimOrAbandon(poll.candidates());
        stateLogger.onTransition(FeedState.FILLING, poll.openFrontCount(), wipLimit);
        return FeedState.FILLING;
    }

    private FeedState idleState(List<ReadyTask> readyTasks, Instant now) {
        List<ReadyTask> backoffEligible = BackoffPolicy.filterEligible(readyTasks, backoffBase, backoffCap, now);
        return backoffEligible.isEmpty() ? FeedState.IDLE_EMPTY : FeedState.IDLE_BLOCKED;
    }

    private Duration jitteredIdleInterval() {
        double jitterFraction = random.nextDouble() * JITTER_MAX_FRACTION;
        long jitterNanos = (long) (idlePollInterval.toNanos() * jitterFraction);
        return idlePollInterval.plusNanos(jitterNanos);
    }
}
