package com.github.oinsio.gnomish.app.sandboxlifecycle;

import com.github.oinsio.gnomish.DoNotMutate;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The daemon's in-memory record of the LAST sweep tick (NFR-O1 of add-serve-sandbox-lifecycle):
 * a {@link SweepVerdictListener} that tallies one tick between {@link #beginTick()} and {@link
 * #endTick()}, then publishes the finished {@link SweepTickRecord} for the snapshot's {@code
 * vitals.sweep} section to read.
 *
 * <p>Per-tick, not cumulative: {@link #beginTick()} discards the previous tally, so the published
 * counts always describe exactly one pass. {@link #lastTick()} keeps the previously published
 * record readable while the next tick is still running, so a concurrent snapshot write never sees
 * a half-counted tick.
 *
 * <p>Only {@link SweepVerdictCategory#KEPT_UNDER_THRESHOLD} verdicts feed the inventory, deduped
 * by task key on the oldest observed age: one kept environment is several objects (box, volume,
 * network) and the operator is deciding about the environment, not about each object.
 *
 * <p>Implements NFR-O1 of add-serve-sandbox-lifecycle.
 */
public final class SweepTickLog implements SweepVerdictListener {

    private final Duration keptReapAge;
    private final Clock clock;
    private final int inventoryBound;

    private final Map<SweepVerdictCategory, Integer> tally = new EnumMap<>(SweepVerdictCategory.class);
    private final Map<String, Duration> keptAges = new LinkedHashMap<>();
    private volatile @Nullable SweepTickRecord lastTick;
    private volatile int consecutiveSkippedTicks;

    /**
     * @param keptReapAge the configured aged-reap threshold every kept environment's remaining
     *     margin is measured against ({@code factory.sandbox.kept-reap-age}); never null
     * @param clock supplies the completed tick's own instant; never null
     * @param inventoryBound how many kept environments the published record carries before
     *     truncating; must be positive
     */
    public SweepTickLog(Duration keptReapAge, Clock clock, int inventoryBound) {
        this.keptReapAge = keptReapAge;
        this.clock = clock;
        this.inventoryBound = inventoryBound;
    }

    /** Discards the previous tally so the next {@link #endTick()} describes this tick alone. */
    public synchronized void beginTick() {
        tally.clear();
        keptAges.clear();
    }

    @Override
    public synchronized void onVerdict(SweepVerdict verdict) {
        tally.merge(verdict.category(), 1, Integer::sum);
        if (verdict.category() != SweepVerdictCategory.KEPT_UNDER_THRESHOLD) {
            return;
        }
        Duration age = verdict.age();
        if (age == null) {
            return;
        }
        keptAges.merge(verdict.taskKey(), age, SweepTickLog::older);
    }

    /**
     * Publishes the tally as the last completed tick and returns it.
     *
     * @return the record just published; never null
     */
    public synchronized SweepTickRecord endTick() {
        List<KeptEnvironment> inventory = inventory();
        consecutiveSkippedTicks =
                tally.containsKey(SweepVerdictCategory.SKIPPED_NO_VERDICT) ? consecutiveSkippedTicks + 1 : 0;
        SweepTickRecord record = new SweepTickRecord(
                clock.instant(), Map.copyOf(tally), inventory, keptAges.size(), consecutiveSkippedTicks);
        lastTick = record;
        return record;
    }

    /**
     * The last completed tick, or null if none has completed in this process yet — the honest
     * "no sweep data yet" state a reader must render rather than invent.
     *
     * @return the last published record, or null
     */
    public @Nullable SweepTickRecord lastTick() {
        return lastTick;
    }

    /**
     * How many ticks in a row have ended with at least one skipped-no-verdict object — the
     * "cleanup silently stalled" signal (NFR-O3), which no single tick's counts can show.
     *
     * @return the current run length; {@code 0} once a tick reaches verdicts again
     */
    public int consecutiveSkippedTicks() {
        return consecutiveSkippedTicks;
    }

    /**
     * Oldest first, then truncated — so the bound keeps the environments CLOSEST to the reap age,
     * the ones an operator can still act on, rather than whichever the listing happened to name
     * first. Ties break on the task key so two ticks over the same host publish the same order.
     */
    private List<KeptEnvironment> inventory() {
        List<Map.Entry<String, Duration>> oldestFirst = new ArrayList<>(keptAges.entrySet());
        oldestFirst.sort(
                Map.Entry.<String, Duration>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        List<KeptEnvironment> inventory = new ArrayList<>();
        for (Map.Entry<String, Duration> entry : oldestFirst) {
            if (inventory.size() == inventoryBound) {
                break;
            }
            inventory.add(new KeptEnvironment(entry.getKey(), entry.getValue(), untilReap(entry.getValue())));
        }
        return inventory;
    }

    /**
     * Clamped at zero: the decision matrix only emits kept-under-threshold below the reap age, so
     * a negative margin would mean the sweep and this sink disagree about the threshold — a
     * disagreement worth rendering as "due now", never worth failing the snapshot write over.
     */
    private Duration untilReap(Duration age) {
        Duration remaining = keptReapAge.minus(age);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * {@code @DoNotMutate}: the {@code >=} / {@code >} boundary mutant is provably equivalent —
     * when the two ages are equal, both arms return a value equal to the other, so no covering
     * test can observe the difference (.claude/rules/testing.md, "provably equivalent mutant").
     */
    @DoNotMutate
    private static Duration older(Duration left, Duration right) {
        return left.compareTo(right) >= 0 ? left : right;
    }
}
