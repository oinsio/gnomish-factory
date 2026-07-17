package com.github.oinsio.gnomish.status;

import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import org.jspecify.annotations.Nullable;

/**
 * The live-only signals a {@link StatusReport} needs that do not survive into a
 * persisted {@code TaskState}: what the engine is doing right now, the most recent
 * escalation report, and — once the run has terminated — the terminal {@link
 * Outcome} (design D7 of add-manual-run).
 *
 * <p>Neither {@code EscalationReport} nor a terminal outcome is carried anywhere on
 * {@code TaskState} — only {@code AttemptRecord}s are — so both are genuinely
 * absent from a state file alone; they exist only for the lifetime of the live
 * process that observed them (design D7). Populated from {@code EngineEvent}s by
 * {@link StatusEventListener} (task 6.2) and from {@code DialogConsole} prompt
 * markers by {@link SnapshotActivityTracker} (task 6.3).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR11, D7 of add-manual-run.
 *
 * @param activity what the engine is doing right now, or {@code null} when nothing
 *     is in flight and no prompt is pending — the JSON contract's {@code activity}
 *     is simply absent when idle, not an {@code idle} variant
 * @param lastEscalation the most recent escalation report observed live, or
 *     {@code null} when none has occurred yet (or the report is built from state
 *     alone)
 * @param outcome the terminal outcome of the run once it has finished, or {@code
 *     null} while the run is still in progress (or the report is built from state
 *     alone)
 */
public record LiveActivity(
        @Nullable Activity activity,
        @Nullable EscalationReport lastEscalation,
        @Nullable Outcome outcome) {

    /**
     * The default activity for a caller with no live signal to report: no activity,
     * no escalation, no outcome. Used by callers with no event-listener adapter
     * wired up (this task's own tests; the future external CLI building a report
     * from a state file alone).
     */
    public static LiveActivity idle() {
        return new LiveActivity(null, null, null);
    }
}
