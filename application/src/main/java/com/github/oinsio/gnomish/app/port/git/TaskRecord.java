package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.baseref.BaseRule;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The task-lifecycle pieces a task repository stores and reads back for one task — its {@link
 * TaskContext}, the commit its branch was created from, when it was created, its terminal outcome
 * (if any), its last escalation, and the durable "tracker-write pending" marker. Kept as a bundle
 * rather than a domain aggregate because no single domain aggregate bundles context, origin and
 * lifecycle outcome yet (design D1 of add-git-workflow: {@code TaskRepository} is a seam separate
 * from the engine).
 *
 * <p>A port-level value type: every component is either a domain type or a plain scalar, so the
 * resume path reads a task's recorded lifecycle without touching the git adapter's {@code
 * task.json} wire format (FR12b, design D12 of split-into-modules). It was previously the git
 * adapter's own {@code TaskJsonContent}, whose {@code outcome} was the {@code task.json} DTO
 * itself; the adapter now maps that DTO onto {@link RecordedOutcome} as it reads.
 *
 * <p>Implements FR3, FR4 of add-git-workflow; FR12b of split-into-modules.
 *
 * @param context the task's identity, description and decisions; never null
 * @param baseCommit the commit the task branch was created from; never null
 * @param createdAt when the task was created; never null
 * @param outcome the recorded terminal outcome, or {@code null} while a visit is in progress
 * @param lastEscalation the last escalation report, or {@code null} if the task was never escalated
 * @param trackerWritePending {@code true} when a recorded terminal park's tracker write is still
 *     outstanding — the durable "tracker-write pending" marker reconcile-on-resume reads (FR10 of
 *     add-claim-heartbeat)
 * @param baseRef the resolved ref {@code baseCommit} was resolved from, or {@code null} when the
 *     task carries no durable pin (a legacy {@code baseCommit}-only document, FR7 of
 *     add-base-ref-resolution)
 * @param baseRule the tier that produced {@code baseRef}, or {@code null} together with {@code
 *     baseRef} when unpinned (FR7 of add-base-ref-resolution)
 */
public record TaskRecord(
        TaskContext context,
        String baseCommit,
        Instant createdAt,
        @Nullable RecordedOutcome outcome,
        @Nullable EscalationReport lastEscalation,
        boolean trackerWritePending,
        @Nullable String baseRef,
        @Nullable BaseRule baseRule) {}
