package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.ClaimResult;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.FeedPolicy;
import com.github.oinsio.gnomish.app.take.OpenFrontGate;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.domain.engine.port.Sleeper;

/**
 * The {@code serve} feed loop: the four-state automaton (design D1) that decides, cycle by cycle,
 * whether to claim, sleep, or wait — driving {@link SlotLedger}, {@link FeedPolicy} (design D2), and
 * {@link Tracker#claim}. The poll-and-claim mechanics live in {@link FeedCycle}, shared verbatim by
 * {@link #step()} and drain mode's {@link #drain()}.
 *
 * <p>The four states (FR5): <b>Full</b> is the ledger's own block — {@link SlotLedger#acquire()}
 * runs before any tracker call, so with no free slot the thread blocks there (zero polls) until a
 * {@link SlotLedger#release}. <b>Filling</b> walks a non-empty candidate list like {@code
 * TakeBareAuto} (a {@link ClaimResult.Held} race loss or an {@link OpenFrontGate} rejection falls
 * through to the next) and loops with no sleep. <b>Idle</b> abandons the permit ({@link
 * SlotLedger#abandon()}) and sleeps the {@link IdleTiming} interval plus a 0-20% jitter (design D4),
 * splitting {@link FeedState#IDLE_EMPTY} (nothing survived the backoff filter) from {@link
 * FeedState#IDLE_BLOCKED} (backoff-eligible but all fresh and WIP-blocked).
 *
 * <p><b>Drain mode</b> (FR10, M3): {@link #drain()} runs the same {@link FeedCycle} as Filling, but
 * the first empty poll stops for good, then blocks on {@link SlotLedger#awaitDrained()}.
 *
 * <p><b>Observability</b>: every feed-state transition is logged once via {@link FeedStateLogger}
 * (NFR-O1, UX2); {@link FeedCycle}'s tracker calls run through {@link FeedOutageRetry}, so a
 * sustained outage is retried with the same jittered idle interval, not propagated (NFR-R3). {@link
 * #view()} exposes the current {@link FeedView} kept by {@link FeedViewTracker} at the same vantage
 * points {@link FeedStateLogger} logs from; {@link #drain()} does not update it (a one-shot CLI run).
 *
 * <p>Implements FR5, FR9, FR10, NFR-O1, NFR-O2, NFR-R3, M3, D1, D4 of add-factory-serve. Implements
 * FR1 of add-serve-observability (design D4): the {@link DirtyNotifier} handed to {@link
 * FeedAssembly} reaches {@link FeedViewTracker}, which wakes it on every actual feed-state transition.
 * Implements FR6 of add-parameter-count-gate (design D7): construction lives in {@link FeedAssembly}.
 */
public final class FeedAutomaton {

    private final SlotLedger slotLedger;
    private final Sleeper sleeper;
    private final Clock clock;
    private final int wipLimit;
    private final IdleTiming idleTiming;

    // Extracted collaborators (NFR-O1 logging, poll-and-claim mechanics, FR5 observability view) —
    // each keeps this class within the file-size limit. The logger is the cycle's own instance, so
    // step() and the cycle's slot-filled vantage point share one once-per-transition latch.
    private final FeedStateLogger stateLogger;
    private final FeedCycle cycle;
    private final FeedViewTracker viewTracker;

    /**
     * The only constructor, package-private: the built {@link FeedCycle} and {@link FeedViewTracker}
     * arrive as values, so this class no longer builds them and nothing outside the package can
     * assemble an automaton except through {@link FeedAssembly} (design D7 of
     * add-parameter-count-gate; the two defaulting constructors had already moved to {@code
     * FeedAutomatonFixture} in {@code :test-fixtures} under D5 of collapse-composition-roots).
     *
     * @param idleTiming the Idle interval and jitter, and the backoff bounds and random source the
     *     feed's selection grades against
     * @param wipLimit the WIP limit W (FR6)
     * @param cycle the poll-and-claim mechanics, carrying the outage retry and the remote outage
     *     gate (FR14, NFR-R3 of add-base-ref-resolution)
     * @param viewTracker the observability view (FR5), seeded with its idle baseline
     */
    FeedAutomaton(
            SlotLedger slotLedger,
            Sleeper sleeper,
            Clock clock,
            IdleTiming idleTiming,
            int wipLimit,
            FeedCycle cycle,
            FeedViewTracker viewTracker) {
        this.slotLedger = slotLedger;
        this.sleeper = sleeper;
        this.clock = clock;
        this.wipLimit = wipLimit;
        this.idleTiming = idleTiming;
        this.stateLogger = cycle.stateLogger();
        this.cycle = cycle;
        this.viewTracker = viewTracker;
    }

    /**
     * The automaton's current observability view (FR5), safe to read from any thread at any time,
     * including mid-cycle or blocked in {@link SlotLedger#acquire()}.
     *
     * @return the most recently observed {@link FeedView}; never null
     */
    public FeedView view() {
        return viewTracker.view();
    }

    /**
     * Runs the automaton until interrupted. Each iteration is exactly {@link #step()}; a caller stops
     * it by interrupting the calling thread, surfacing at the next {@link SlotLedger#acquire()} block.
     */
    @SuppressWarnings("InfiniteLoopStatement") // intentional: runs until the caller interrupts
    public void run() throws InterruptedException {
        while (true) {
            step();
        }
    }

    /**
     * Drain mode (FR10, NFR-O2, M3): claims and runs eligible tasks like {@link #step()}'s Filling
     * path, but the first empty poll stops for good (no idle sleep, no re-poll), then blocks on
     * {@link SlotLedger#awaitDrained()} so every already-running slot finishes; an empty queue at the
     * first poll returns immediately. Outcome collection is {@code ServeCommand}'s job — it attaches
     * a {@link DrainReport} to the real {@link SlotRunner} first, since only the slot body knows each
     * task's terminal outcome.
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
        // FR5: checked before the blocking acquire(), so a reader sees FULL while this call is parked.
        if (slotLedger.freeSlots() == 0) {
            viewTracker.transitionTo(FeedState.FULL, clock.now());
        }
        slotLedger.acquire();
        FeedCycle.Poll poll = cycle.poll(clock.now());
        viewTracker.recordPoll(poll.now(), poll.openFrontCount());

        if (poll.candidates().isEmpty()) {
            slotLedger.abandon();
            FeedState idleState = idleTiming.idleState(poll.readyTasks(), poll.now());
            stateLogger.onTransition(idleState, poll.openFrontCount(), wipLimit);
            viewTracker.transitionTo(idleState, poll.now());
            sleeper.sleep(idleTiming.jittered());
            return idleState;
        }

        cycle.claimOrAbandon(poll.candidates());
        stateLogger.onTransition(FeedState.FILLING, poll.openFrontCount(), wipLimit);
        viewTracker.transitionTo(FeedState.FILLING, poll.now());
        return FeedState.FILLING;
    }
}
