package com.github.oinsio.gnomish.adapter.git.state;

import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The domain-level pieces bundled by {@code task.json}, returned by {@link
 * TaskJsonMapper#fromDto(TaskJsonDto)} — scoped to this mapper, not part of the
 * domain layer, since no single domain aggregate bundles {@link TaskContext},
 * origin, and lifecycle outcome yet (design D1: {@code TaskRepository} is a
 * seam separate from the engine).
 *
 * <p>{@code outcome} is kept at the DTO level ({@link TaskOutcomeDto}, not the
 * domain's {@code TaskOutcome}) because {@code task.json} alone lacks the data
 * a domain {@code TaskOutcome} requires — {@code finalState} lives in {@code
 * state.json} (task 1.3's contract) and {@code Aborted.failedAt}'s {@code
 * AttemptKey} is rendered on the wire only as an opaque label. {@code
 * lastEscalation} round-trips fully into the domain {@link EscalationReport}:
 * every field an {@code EscalationReport} variant needs is present on the wire.
 *
 * <p>Implements FR3, FR4 of add-git-workflow.
 *
 * @param context the task's identity, description and decisions; never null
 * @param baseCommit the commit the task branch was created from; never null
 * @param createdAt when the task was created; never null
 * @param outcome the terminal outcome at the DTO level, or {@code null} while a
 *     visit is in progress
 * @param lastEscalation the last escalation report, or {@code null} if the task
 *     was never escalated
 */
public record TaskJsonContent(
        TaskContext context,
        String baseCommit,
        Instant createdAt,
        @Nullable TaskOutcomeDto outcome,
        @Nullable EscalationReport lastEscalation) {}
