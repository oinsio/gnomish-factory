package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import java.time.Instant;

/**
 * One occupied slot as {@link SlotLedger} tracks it internally: the task ref tied to the slot
 * and the instant {@link SlotLedger#assign(TaskRef)} captured it (design D6's "shared need of
 * FR6 and FR11"). This is the ledger's own read model, not the snapshot's — the snapshot's
 * {@code SlotEntry} (FR6) is built from this plus the runner's durable-progress {@code
 * stage}/{@code attempt} (task 2.2, design D11), and a {@code taskOutcome} line's {@code
 * startedAt} (FR11) is read from {@link #since()} at the slot's terminal result.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR6, FR11 of add-serve-observability.
 *
 * @param taskId the task ref occupying the slot; never null
 * @param since the instant {@link SlotLedger#assign(TaskRef)} tied this ref to the slot; never
 *     null
 */
public record OccupiedSlot(TaskRef taskId, Instant since) {}
