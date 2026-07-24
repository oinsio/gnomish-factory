package com.github.oinsio.gnomish.app.take;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.tracker.AbortFacts;
import com.github.oinsio.gnomish.app.port.tracker.ReadyTask;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Exponential abort-backoff policy applied by core over adapter-reported
 * {@link AbortFacts} to hide recently-aborted entries from the bare auto
 * {@code take} feed (design D10): {@code delay = base * 2^(count-1)}, capped at
 * {@code cap}. A {@code Ready} task stays invisible while {@code now -
 * lastAbortAt < delay}.
 *
 * <p>This class is pure logic — it takes {@code base}/{@code cap} as explicit
 * {@link Duration} parameters rather than reading {@code
 * factory.tracker.abort-backoff-base}/{@code -cap} itself; binding those config
 * keys onto {@code FactoryProperties} and passing them in is a later wiring
 * task. Per design D5 the intended defaults are {@code base=2m}, {@code
 * cap=1h}; {@link #DEFAULT_BASE} and {@link #DEFAULT_CAP} mirror them here for
 * tests' convenience only.
 *
 * <p>Explicit {@code take <ref>} ignores backoff entirely (mandate) — that mode
 * simply never calls {@link #filterEligible}; no code branch is needed here for
 * it.
 *
 * <p>Implements FR10, D10, NFR-C1 of add-tracker-port.
 */
public final class BackoffPolicy {

    /** Default backoff base per design D5: {@code 2m}. Config binding is 5.15's job. */
    public static final Duration DEFAULT_BASE = Duration.ofMinutes(2);

    /** Default backoff cap per design D5: {@code 1h}. Config binding is 5.15's job. */
    public static final Duration DEFAULT_CAP = Duration.ofHours(1);

    private BackoffPolicy() {}

    /**
     * Computes the exponential backoff delay for the given abort {@code count}:
     * {@code base * 2^(count-1)}, capped at {@code cap} (design D10).
     *
     * <p>Implements FR10, D10 of add-tracker-port.
     *
     * @param count aborts since last durable progress; {@code count <= 0} yields
     *     {@link Duration#ZERO} ("nothing to back off" — {@link AbortFacts#none()}
     *     never reaches this path in practice, since {@code count == 0} always
     *     pairs with a null {@code lastAbortAt})
     * @param base the backoff base for a single abort; never null, non-negative
     * @param cap the maximum backoff delay; never null, non-negative
     * @return the capped exponential delay; never null
     */
    public static Duration delay(int count, Duration base, Duration cap) {
        if (count <= 0) {
            return Duration.ZERO;
        }
        var raw = base.multipliedBy(1L << (count - 1));
        return capped(raw, cap);
    }

    // PIT M4 documented exception (build.gradle has the full rationale style): @DoNotMutate — `>`
    // vs `>=` (ConditionalsBoundaryMutator) is a genuine equivalent mutant here: the two branches
    // only disagree at raw.compareTo(cap) == 0 (raw exactly equals cap), and at that exact input
    // both branches return a Duration equal in value to the other (`cap` vs `raw`, with
    // raw.equals(cap) true) — no test can observe a difference between them via Duration#equals.
    // BackoffPolicySpec's "delay at the exact boundary" scenario proves the value returned at this
    // boundary is correct; it cannot additionally distinguish which branch produced it.
    @DoNotMutate
    private static Duration capped(Duration raw, Duration cap) {
        return raw.compareTo(cap) > 0 ? cap : raw;
    }

    /**
     * Whether the task is currently backed off (design D10): {@code true} iff
     * {@code facts.count() > 0} and {@code now - facts.lastAbortAt() < delay}.
     * A task with no abort history ({@code count == 0}) is never backed off.
     *
     * <p>Implements FR10, D10 of add-tracker-port.
     *
     * @param facts the task's abort history; never null
     * @param base the backoff base for a single abort; never null
     * @param cap the maximum backoff delay; never null
     * @param now the instant to evaluate backoff against; never null
     * @return {@code true} iff the task's backoff window has not yet expired
     */
    public static boolean isBackedOff(AbortFacts facts, Duration base, Duration cap, Instant now) {
        if (facts.count() <= 0) {
            return false;
        }
        var delay = delay(facts.count(), base, cap);
        return now.minus(delay).isBefore(facts.lastAbortAt());
    }

    /**
     * Filters a {@code listReady} result down to entries eligible for bare auto
     * {@code take}: backed-off entries (per {@link #isBackedOff}) are dropped,
     * adapter queue order is preserved for the rest (FR10, D10). The caller (task
     * 5.10's bare-mode flow) claims the head of this filtered list.
     *
     * <p>Implements FR10, D10, NFR-C1 of add-tracker-port.
     *
     * @param readyTasks the adapter's {@code listReady} result, in queue order;
     *     never null
     * @param base the backoff base for a single abort; never null
     * @param cap the maximum backoff delay; never null
     * @param now the instant to evaluate backoff against; never null
     * @return the eligible entries, in the original adapter order; never null
     */
    public static List<ReadyTask> filterEligible(List<ReadyTask> readyTasks, Duration base, Duration cap, Instant now) {
        return readyTasks.stream()
                .filter(task -> !isBackedOff(task.abortFacts(), base, cap, now))
                .toList();
    }
}
