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
 * <p>Implements FR5, D4 of add-factory-serve.
 */
final class IdleTiming {

    /** Design D4: up to +20% jitter on the idle interval. */
    private static final double JITTER_MAX_FRACTION = 0.20;

    private final Duration idlePollInterval;
    private final Duration backoffBase;
    private final Duration backoffCap;
    private final Random random;

    IdleTiming(Duration idlePollInterval, Duration backoffBase, Duration backoffCap, Random random) {
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
}
