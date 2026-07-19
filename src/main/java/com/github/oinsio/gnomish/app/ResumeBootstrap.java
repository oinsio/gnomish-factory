package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The handoff bundle {@link GitResumeRunner#bootstrap} produces once a resumed task's branch is
 * located, its worktree materialized, and its {@code task.json} loaded and version-gated (FR8,
 * design D9, task 4.6): everything the outcome-driven continuation (task 4.7) needs to switch on
 * {@code outcome} without re-deriving any of it.
 *
 * <p>{@code outcome} is kept at the DTO level ({@link TaskOutcomeDto}, not the domain {@code
 * TaskOutcome}) for the same reason {@code TaskJsonContent} does — {@code task.json} alone lacks
 * {@code state.json}'s {@code finalState} needed to rebuild a full domain {@code TaskOutcome};
 * task 4.7 is expected to join this with a freshly-read {@code state.json} the way {@code
 * GitTaskRepository} does internally.
 *
 * <p>Implements FR8 of add-git-workflow.
 *
 * @param taskId the tracker's original (un-sanitized) taskId, as supplied to {@code --resume}
 * @param context the resumed task's identity, description and decisions, read from {@code
 *     task.json}
 * @param outcome the task's recorded outcome at the DTO level, or {@code null} if the last visit
 *     ended without recording one (process death, FR8's "outcome null" case)
 * @param lastEscalation the last escalation report, or {@code null} if the task was never
 *     escalated
 * @param worktreePath the materialized worktree's absolute path; ready to use as-is
 * @param branchName the task branch's short name, e.g. {@code gnomish/PROJ-1}
 * @param baseCommit the commit the task branch was created from, as recorded in {@code task.json}
 */
public record ResumeBootstrap(
        String taskId,
        TaskContext context,
        @Nullable TaskOutcomeDto outcome,
        @Nullable EscalationReport lastEscalation,
        Path worktreePath,
        String branchName,
        String baseCommit) {}
