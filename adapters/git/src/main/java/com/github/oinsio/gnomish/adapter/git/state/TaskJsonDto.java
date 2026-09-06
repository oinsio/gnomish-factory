package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code task.json} v1 contract's top-level shape (design D3): identity,
 * origin, chronological decisions, the terminal {@code outcome}, and {@code
 * lastEscalation} kept separately so it survives an outcome reset on resume
 * (FR5), and the {@code egressCursor} the escalation's drained denials were read
 * up to (FR3 of fix-denial-attribution-durability).
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * @param version the state-file contract version, {@code 1}
 * @param taskId the opaque task identifier
 * @param title the task's human title
 * @param body the task's human description
 * @param createdAt ISO-8601 UTC instant the task was created
 * @param baseCommit the commit the task branch was created from
 * @param decisions the chronological human decisions
 * @param outcome the terminal outcome, or {@code null} while a visit is in
 *     progress
 * @param lastEscalation the last escalation report, or {@code null} if the task
 *     was never escalated
 * @param trackerWritePending {@code true} when a terminal park outcome
 *     ({@code Escalated}/{@code Paused}) was recorded here but its tracker write
 *     has not been confirmed landed — the durable "tracker-write pending" marker
 *     reconcile-on-resume reads to complete an orphaned park (FR10 of
 *     add-claim-heartbeat); {@code null}/{@code false} means no park write is
 *     outstanding
 * @param egressCursor the environment's denial read position as it stood when the
 *     escalation beside it was recorded, or {@code null} when there is none —
 *     environment bookkeeping, never task state, and never rendered into {@code
 *     status.json} (FR3 of fix-denial-attribution-durability); additive under
 *     contract v1, so a document written before the field existed binds it to
 *     {@code null} and the run reads its denial source from the start
 */
public record TaskJsonDto(
        int version,
        String taskId,
        String title,
        String body,
        String createdAt,
        String baseCommit,
        List<TaskDecisionDto> decisions,
        @Nullable TaskOutcomeDto outcome,
        @Nullable EscalationReportDto lastEscalation,
        @Nullable Boolean trackerWritePending,
        @Nullable EgressCursorDto egressCursor) {

    /**
     * This document with the tracker-write-pending marker set to {@code pending} and every other
     * field carried forward verbatim — the shape a marker rewrite needs (FR10 of
     * add-claim-heartbeat).
     *
     * <p>A wither rather than a re-listed canonical constructor at each rewrite site (design D8 of
     * fix-denial-attribution-durability): a positional rebuild silently drops whatever field the
     * contract gained since it was written, which is exactly how the denial cursor was erased on
     * {@code state.json}'s RESUMED rewrite.
     *
     * <p>PIT documented exception (`.claude/rules/testing.md`, JVMTI redefinition limit):
     * {@code @DoNotMutate} because PIT's Gregor engine crashes its own minion JVM (RUN_ERROR, not
     * a real test gap) mutating this record's wither on JDK 17+ (hcoles/pitest#1285, a JVMTI
     * RedefineClasses restriction on NestHost/NestMembers/Record attributes — not fixable via PIT
     * config). Preservation of every other field is asserted by {@code TaskJsonMapperSpec} and by
     * both lifecycle stores' marker-clearing specs.
     *
     * @param pending the new marker value, or {@code null} for "no park write outstanding"
     * @return this document with the marker replaced
     */
    @DoNotMutate
    public TaskJsonDto withTrackerWritePending(@Nullable Boolean pending) {
        return new TaskJsonDto(
                version,
                taskId,
                title,
                body,
                createdAt,
                baseCommit,
                decisions,
                outcome,
                lastEscalation,
                pending,
                egressCursor);
    }

    /**
     * This document with the environment's denial read position replaced and every other field
     * carried forward verbatim — how the {@code cannotExecute} park attaches the position its
     * drained denials were read up to, in the same commit as the escalation (FR3 of
     * fix-denial-attribution-durability).
     *
     * @param cursor the position to record, or {@code null} for "no cursor to resume from"
     * @return this document with the cursor replaced
     */
    public TaskJsonDto withEgressCursor(@Nullable EgressCursorDto cursor) {
        return new TaskJsonDto(
                version,
                taskId,
                title,
                body,
                createdAt,
                baseCommit,
                decisions,
                outcome,
                lastEscalation,
                trackerWritePending,
                cursor);
    }
}
