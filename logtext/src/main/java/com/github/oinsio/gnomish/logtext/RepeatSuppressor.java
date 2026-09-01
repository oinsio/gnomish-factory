package com.github.oinsio.gnomish.logtext;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.Nullable;

/**
 * The one owner of repeat suppression for poll and retry loops (FR4 of
 * harden-logging-observability): a loop that can fail on every tick reports each outcome here and
 * logs the {@link RepeatOccurrence} it gets back, so the operator sees <em>edges</em> — the fault
 * arriving, a periodic reminder that it is still there, and the recovery — instead of one line per
 * tick.
 *
 * <p>It decides which form applies and never logs: levels belong to the call site, which knows
 * whether its own failure is a WARN or a DEBUG. A changed reason for the same subject restarts the
 * streak, because a different fault is news even while the subject stays broken.
 *
 * <p>State is in-memory per process (NFR-R2): no durable record, no recovery owner, no expiry
 * beyond {@link #recovered}. A restart resets it and the first-occurrence line is emitted again,
 * which is correct — a fresh process has told the operator nothing yet.
 *
 * <p>Sites that flood <em>within one operation</em> rather than across calls — a parse loop over
 * one file, a bulk deletion — count locally and emit one aggregate line instead. That is a
 * different invariant (aggregate-per-call, not edge-across-calls) and deliberately not this class.
 *
 * <p>Thread-safe: every mutation is one {@link ConcurrentMap#compute} on the subject's key, so
 * concurrent reporters of the same subject serialize on it and each gets a distinct verdict.
 *
 * <p>Implements FR4, NFR-R2 of harden-logging-observability.
 */
public final class RepeatSuppressor {

    /**
     * How long a streak stays quiet between roll-ups. Long enough that a fault lasting an hour
     * costs the console a handful of lines rather than a flood, short enough that an operator
     * arriving mid-outage learns of it without waiting for the recovery.
     */
    public static final Duration DEFAULT_ROLL_UP_INTERVAL = Duration.ofMinutes(5);

    private final Clock clock;
    private final Duration rollUpInterval;
    private final ConcurrentMap<String, Streak> streaks = new ConcurrentHashMap<>();

    /**
     * @param clock the time source the roll-up interval and the outage duration are measured on;
     *     never null — a spec drives it on virtual time
     * @param rollUpInterval the quiet period between roll-ups; never null, must be positive
     * @throws IllegalArgumentException if {@code rollUpInterval} is not positive
     */
    public RepeatSuppressor(Clock clock, Duration rollUpInterval) {
        if (rollUpInterval.isNegative() || rollUpInterval.isZero()) {
            throw new IllegalArgumentException("rollUpInterval must be positive, got " + rollUpInterval);
        }
        this.clock = clock;
        this.rollUpInterval = rollUpInterval;
    }

    /**
     * The production wiring: the system clock and the default roll-up interval. For the composition
     * root, never for a spec — a spec builds the two-argument constructor with virtual time, which
     * the {@code checkTestTimeInjection} gate enforces.
     *
     * @return a suppressor on real time; never null
     */
    public static RepeatSuppressor system() {
        return new RepeatSuppressor(Clock.systemUTC(), DEFAULT_ROLL_UP_INTERVAL);
    }

    /**
     * Reports that {@code key} failed for {@code reason} and answers how the site should log it.
     *
     * @param key the subject whose streak this is — the call site's own identity plus what it was
     *     acting on (a poll target, a branch ref); never null
     * @param reason the failure reason, phrased so that a different fault reads as a different
     *     string; never null
     * @return the form this occurrence takes; never null
     */
    public RepeatOccurrence failed(String key, String reason) {
        Instant now = clock.instant();
        Streak streak = streaks.compute(key, (ignored, previous) -> advance(previous, reason, now));
        return streak.occurrence();
    }

    /**
     * Reports that {@code key} succeeded, ending any streak it had.
     *
     * @param key the subject reported to {@link #failed}; never null
     * @return the ended streak when there was one — the recovery line's facts — else empty, which
     *     is the steady state and logs nothing; never null
     */
    public Optional<RepeatRecovery> recovered(String key) {
        Streak ended = streaks.remove(key);
        if (ended == null) {
            return Optional.empty();
        }
        return Optional.of(new RepeatRecovery(
                ended.reason(), ended.count(), Duration.between(ended.startedAt(), clock.instant())));
    }

    /**
     * The streak transition, run inside the map's per-key lock: a first failure (or a changed
     * reason) restarts it, a repetition past the quiet period is a roll-up and re-arms it, anything
     * else is a plain repeat that leaves the roll-up clock alone.
     */
    private Streak advance(@Nullable Streak previous, String reason, Instant now) {
        if (previous == null || !previous.reason().equals(reason)) {
            return new Streak(reason, now, now, 1, new RepeatOccurrence.First(reason));
        }
        long count = previous.count() + 1;
        if (now.isBefore(previous.announcedAt().plus(rollUpInterval))) {
            return new Streak(
                    reason,
                    previous.startedAt(),
                    previous.announcedAt(),
                    count,
                    new RepeatOccurrence.Repeat(reason, count));
        }
        Duration elapsed = Duration.between(previous.startedAt(), now);
        return new Streak(
                reason, previous.startedAt(), now, count, new RepeatOccurrence.RollUp(reason, count, elapsed));
    }

    /**
     * One subject's failure streak. Immutable, replaced wholesale on each transition, and carrying
     * the verdict that produced it so {@link #failed} reads both the new state and its answer out
     * of the one atomic {@code compute}.
     *
     * @param reason the reason the streak is running under
     * @param startedAt when the streak's first failure was reported
     * @param announcedAt when the operator was last told at the site's own level (first occurrence
     *     or roll-up) — what the quiet period is measured from
     * @param count how many failures the streak has seen
     * @param occurrence the verdict for the failure that produced this state
     */
    private record Streak(
            String reason, Instant startedAt, Instant announcedAt, long count, RepeatOccurrence occurrence) {}
}
