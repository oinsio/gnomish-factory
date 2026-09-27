package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import com.github.oinsio.gnomish.app.take.BackoffPolicy;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;

/**
 * The {@link FeedAutomaton}'s Idle-state timing, extracted so that class stays within the file-size
 * limit (process-invariants.md): the jittered poll interval reused as both the Idle sleep and the
 * outage-retry pause (NFR-R3), and the empty-vs-blocked classification of an empty candidate list.
 *
 * <p>Built by the composition root and handed to {@link FeedAutomaton} whole: it already owns the
 * backoff bounds and the random source the feed's selection grades against, so the automaton's
 * constructor takes one value in place of four (D5 of collapse-composition-roots).
 *
 * <p>Implements FR5, D4 of add-factory-serve.
 */
public final class IdleTiming {

    /** Design D4: up to +20% jitter on the idle interval. */
    private static final double JITTER_MAX_FRACTION = 0.20;

    private final Duration idlePollInterval;
    private final Duration backoffBase;
    private final Duration backoffCap;
    private final Random random;

    /**
     * @param idlePollInterval the shared Idle poll interval (FR5 of add-factory-serve)
     * @param backoffBase the abort-backoff lower bound (design D10 of add-tracker-port)
     * @param backoffCap the abort-backoff upper bound
     * @param random the head-zone pick and idle jitter source (seeded = deterministic)
     */
    public IdleTiming(Duration idlePollInterval, Duration backoffBase, Duration backoffCap, Random random) {
        this.idlePollInterval = idlePollInterval;
        this.backoffBase = backoffBase;
        this.backoffCap = backoffCap;
        this.random = random;
    }

    // The Idle poll interval plus a uniform 0-20% jitter (design D4); deterministic with a seeded
    // random. Reused verbatim as the FeedOutageRetry pause (NFR-R3).
    Duration jittered() {
        double jitterFraction = random.nextDouble() * JITTER_MAX_FRACTION;
        long jitterNanos = (long) (idlePollInterval.toNanos() * jitterFraction);
        return idlePollInterval.plusNanos(jitterNanos);
    }

    // IDLE_EMPTY when nothing survives the backoff filter, IDLE_BLOCKED when backoff-eligible
    // entries existed but were all fresh and WIP-blocked (mirrors TakeBareAuto's empty split).
    FeedState idleState(List<ReadyTask> readyTasks, Instant now) {
        List<ReadyTask> backoffEligible = BackoffPolicy.filterEligible(readyTasks, backoffBase, backoffCap, now);
        return backoffEligible.isEmpty() ? FeedState.IDLE_EMPTY : FeedState.IDLE_BLOCKED;
    }

    // The feed's selection over the same backoff bounds and random source, under the WIP limit W
    // (FR6) — so the Idle split and the claim filter can never grade against different bounds.
    FeedSelection selection(int wipLimit) {
        return new FeedSelection(backoffBase, backoffCap, wipLimit, random);
    }
}
