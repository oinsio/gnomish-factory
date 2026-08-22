package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.sandboxlifecycle.KeptEnvironment;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickLog;
import com.github.oinsio.gnomish.app.sandboxlifecycle.SweepTickRecord;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Builds the snapshot's {@code vitals.sweep} entry from the daemon's {@link SweepTickLog}
 * (NFR-O1 of add-serve-sandbox-lifecycle): the open per-category tally becomes the closed {@link
 * SweepCounts} record, and each kept environment's {@link Duration}s become the seconds the wire
 * contract carries. Returns null while no tick has completed — the section is absent rather than
 * zero-filled, so "the daemon just started" and "the sweep counted nothing" stay distinguishable.
 *
 * <p>Stateless: a pure function with no fields, following the module's assembler convention (e.g.
 * {@link LifecycleLineAssembler}, {@link TrackerHealthAssembler}).
 *
 * <p>Implements NFR-O1 of add-serve-sandbox-lifecycle.
 */
public final class SweepVitalAssembler {

    private SweepVitalAssembler() {}

    /**
     * Assembles the {@code vitals.sweep} entry from {@code tickLog}'s last completed tick.
     *
     * @param tickLog the daemon's per-tick record; never null
     * @param interval the sweep tick cadence, the reader's staleness yardstick; never null
     * @return the assembled entry, or null when no tick has completed in this process
     */
    public static @Nullable SweepVital assemble(SweepTickLog tickLog, Duration interval) {
        SweepTickRecord lastTick = tickLog.lastTick();
        if (lastTick == null) {
            return null;
        }
        return new SweepVital(
                lastTick.tickAt(),
                interval.toSeconds(),
                SweepCounts.of(lastTick.counts()),
                toEntries(lastTick.kept()),
                lastTick.keptTotal(),
                lastTick.consecutiveSkippedTicks());
    }

    private static List<KeptEnvironmentEntry> toEntries(List<KeptEnvironment> kept) {
        return kept.stream()
                .map(entry -> new KeptEnvironmentEntry(
                        entry.taskKey(),
                        entry.age().toSeconds(),
                        entry.untilReap().toSeconds()))
                .toList();
    }
}
