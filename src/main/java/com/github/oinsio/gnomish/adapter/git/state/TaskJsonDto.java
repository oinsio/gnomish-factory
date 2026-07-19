package com.github.oinsio.gnomish.adapter.git.state;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The {@code task.json} v1 contract's top-level shape (design D3): identity,
 * origin, chronological decisions, the terminal {@code outcome}, and {@code
 * lastEscalation} kept separately so it survives an outcome reset on resume
 * (FR5).
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
        @Nullable EscalationReportDto lastEscalation) {}
