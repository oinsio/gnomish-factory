package com.github.oinsio.gnomish.serveobservability.writer;

import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.serve.OccupiedSlot;
import com.github.oinsio.gnomish.app.serve.SlotLedger;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.serveobservability.InstanceInfo;
import com.github.oinsio.gnomish.serveobservability.TaskOutcomeLine;
import com.github.oinsio.gnomish.serveobservability.TaskOutcomeLineAssembler;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code TakeSlotRunner} write point for the ledger's {@code taskOutcome} line (design D6,
 * FR11): beside {@code drainReport.record()}, a slot calls {@link #write(TaskRef, TakeResult)}
 * with its terminal result. Looks up the slot's {@code since} from {@link SlotLedger} — read
 * before the caller releases the slot, exactly like {@link
 * com.github.oinsio.gnomish.serveobservability.SlotEntryAssembler} reads the same source for the
 * snapshot (FR6, FR11 share this need per design D6) — assembles the line via {@link
 * TaskOutcomeLineAssembler}, and appends it through the shared {@link RotatingLedgerAppender}.
 * {@link TakeResult.EmptyQueue}/{@link TakeResult.Skipped} assemble to no line, so this writes
 * nothing for them.
 *
 * <p>Write failures never propagate (NFR-R1): an {@link IOException} from the appender is logged
 * and swallowed, exactly as a slot must never crash the daemon or fail a task over an
 * observability write.
 *
 * <p>Implements FR11, NFR-R1, D6 of add-serve-observability.
 */
public final class TaskOutcomeLedgerWriter {

    private static final Logger log = LoggerFactory.getLogger(TaskOutcomeLedgerWriter.class);

    private final SlotLedger slotLedger;
    private final RotatingLedgerAppender appender;
    private final InstanceInfo instance;
    private final Clock clock;

    /**
     * @param slotLedger the same slot registry the finishing slot's caller has not yet released
     *     {@code claimed} from; never null
     * @param appender the shared ledger append point every line is written through; never null
     * @param instance this factory instance's identity, carried on every written line; never null
     * @param clock supplies {@code finishedAt} for every write; never null
     */
    public TaskOutcomeLedgerWriter(
            SlotLedger slotLedger, RotatingLedgerAppender appender, InstanceInfo instance, Clock clock) {
        this.slotLedger = slotLedger;
        this.appender = appender;
        this.instance = instance;
        this.clock = clock;
    }

    /**
     * Writes {@code result}'s ledger line for {@code claimed}, or does nothing for {@link
     * TakeResult.EmptyQueue}/{@link TakeResult.Skipped}.
     *
     * @param claimed the task whose slot just reached a terminal result; never null
     * @param result the terminal result the slot reached; never null
     */
    public void write(TaskRef claimed, TakeResult result) {
        Instant startedAt = startedAtFor(claimed);
        if (startedAt == null) {
            log.warn(
                    OperatorEvent.TASK_OUTCOME_SLOT_MISSING.head()
                            + "no occupied slot entry for task {}; skipping taskOutcome ledger line",
                    claimed.id());
            return;
        }
        TaskOutcomeLine line =
                TaskOutcomeLineAssembler.assemble(instance, claimed.id(), result, startedAt, clock.instant());
        if (line == null) {
            return;
        }
        try {
            appender.append(line);
        } catch (IOException e) {
            log.error(
                    OperatorEvent.TASK_OUTCOME_LEDGER_APPEND_FAILED.head()
                            + "failed to append taskOutcome ledger line for task {}",
                    claimed.id(),
                    e);
        }
    }

    private @Nullable Instant startedAtFor(TaskRef claimed) {
        return slotLedger.occupiedEntries().stream()
                .filter(entry -> entry.taskId().equals(claimed))
                .map(OccupiedSlot::since)
                .findFirst()
                .orElse(null);
    }
}
