package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.lease.HeartbeatProgress;
import com.github.oinsio.gnomish.app.serve.OccupiedSlot;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Builds the snapshot's {@code slots} entries (FR6) by combining {@link
 * SlotLedger#occupiedEntries()} (which task occupies each slot, and since when) with {@link
 * HeartbeatProgress#progressFor(String)} (that task's latest known {@code stage}/{@code
 * attempt}) — the two state sources design D6/D11 name for this join. Occupancy and progress
 * are read from two independently-updated sources, so {@code stage}/{@code attempt} may lag up
 * to {@code intervalSeconds} behind occupancy itself: slot assign/release are immediate-write
 * triggers (FR1), but a stage transition deliberately is not (design D11) — no alert rule reads
 * {@code stage}, and a fast pipeline must not become a snapshot write storm.
 *
 * <p>A {@code stage} of {@link HeartbeatProgress#PENDING} (no engine event has arrived yet for a
 * freshly-claimed task) or of {@link HeartbeatProgress#PIPELINE_END_STAGE} (the run resolved to
 * the explicit end of the pipeline) is not informative about "where in the pipeline" a task is,
 * so both map to a {@code null} {@link SlotEntry#stage()} rather than leaking the sentinel string
 * into the snapshot (per {@link SlotEntry}'s contract).
 *
 * <p>Stateless: holds no fields, only assembles a fresh {@link List} from the two sources handed
 * to it on each call.
 *
 * <p>Implements FR6, D11 of add-serve-observability.
 */
public final class SlotEntryAssembler {

    private SlotEntryAssembler() {}

    /**
     * Assembles one {@link SlotEntry} per currently-occupied slot.
     *
     * @param ledger the slot registry whose currently-occupied slots (taskId + since) become
     *     entries; never null
     * @param progress the durable-progress hook whose latest {@code (stage, attempt)} per task
     *     enriches each entry; never null
     * @return one {@link SlotEntry} per currently-occupied slot, in no particular order;
     *     possibly empty; never null
     */
    public static List<SlotEntry> assemble(SlotLedger ledger, HeartbeatProgress progress) {
        return ledger.occupiedEntries().stream()
                .map(occupied -> toEntry(occupied, progress))
                .toList();
    }

    private static SlotEntry toEntry(OccupiedSlot occupied, HeartbeatProgress progress) {
        HeartbeatProgress.Progress latest =
                progress.progressFor(occupied.taskId().id());
        return new SlotEntry(occupied.taskId().id(), stageOrNull(latest), latest.attempt(), occupied.since());
    }

    private static @Nullable String stageOrNull(HeartbeatProgress.Progress progress) {
        if (progress.equals(HeartbeatProgress.PENDING)
                || HeartbeatProgress.PIPELINE_END_STAGE.equals(progress.stage())) {
            return null;
        }
        return progress.stage();
    }
}
