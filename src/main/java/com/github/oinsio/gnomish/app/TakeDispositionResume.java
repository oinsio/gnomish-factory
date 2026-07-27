package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.io.UncheckedIOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The "branch already exists" half of {@link TakeDisposition}'s {@code Ready} case (FR9, D3):
 * bootstraps the existing branch, then dispatches on its recorded {@code task.json} outcome. An
 * {@code Escalated} outcome whose report is an ESCALATION kind ({@link
 * EscalationReport.AttemptsExhausted}/{@link EscalationReport.DecisionNeeded}) goes through {@link
 * TakeDecisionResume}; every other recorded outcome — {@code null} (died mid-visit), {@code
 * Paused} (checkpoint), an INFRA-kind {@code Escalated}, or {@code Aborted} — resumes on the
 * human's return alone via {@link TakeResumeRunner#resumeWithoutDecision} (FR9, D12: "any other
 * recorded outcome continues on the return alone").
 *
 * <p>{@code Aborted} is deliberately NOT refused here (contrast manual-run {@link
 * GitResumeRunner}, which refuses it): the abort protocol (FR14) intentionally returns a
 * below-K abort to {@code Ready} so a later claim retries it from the last durably recorded
 * {@code state.json} position. Refusing would strand every below-K abort as permanently
 * un-takeable and break the K-fuse retry loop. The one refusal left is a delivered-and-cleaned-up
 * branch the tracker still reports {@code Ready} (task.json already {@code git rm}'d on {@code
 * Completed}, FR15) — a genuine tracker/branch inconsistency a human must reconcile, surfaced from
 * the {@link NoSuchFileException} below.
 *
 * <p>Split out of {@link TakeDisposition} purely to respect the file-size guidance
 * (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR9, D3 of add-tracker-port.
 */
record TakeDispositionResume(TakeResumeRunner resumeRunner, TakeDecisionResume decisionResume) {

    /**
     * Bootstraps the existing branch for {@code taskId} and dispatches on its recorded outcome
     * (see class javadoc).
     *
     * <p>Implements FR9, D3 of add-tracker-port.
     */
    TakeResult resumeExisting(
            Path cloneDir,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            String taskId,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        ResumeBootstrap bootstrap;
        try {
            bootstrap = resumeRunner.bootstrap(cloneDir, taskId);
        } catch (UncheckedIOException e) {
            if (e.getCause() instanceof NoSuchFileException) {
                // Rare inconsistency: the branch's own cleanup commit (GitTaskRepository#
                // recordOutcome on Completed, FR15) already removed .gnomish-task/ from the tip
                // entirely (task.json AND state.json both gone, in the same commit) — this is what
                // "the branch says done but the tracker still reported Ready" looks like on disk;
                // a human force-flipped the label back without going through the normal finish
                // protocol. There is no live task.json/state.json left to read a finalState from
                // at the tip (only branch history has it, and no adapter-layer API exposes reading
                // an arbitrary historical revision today) — so this is refused rather than
                // fabricated as a Delivered result with an invented TaskState.
                throw new UsageException("cannot take task \"" + taskId
                        + "\": its branch already recorded completion and its .gnomish-task/ files were"
                        + " cleaned up — the task is already delivered; this is a tracker/branch"
                        + " inconsistency (the tracker still reports Ready) that needs a human to reconcile"
                        + " labels, not a resume");
            }
            throw e;
        }
        TaskState finalState = GitFreshTaskSupport.readFinalState(bootstrap.worktreePath());
        TaskOutcomeDto outcome = bootstrap.outcome();

        // Route only a genuine ESCALATION-kind park through the decision dialog: the recorded
        // outcome is Escalated AND its report is AttemptsExhausted/DecisionNeeded (design D3). The
        // outcome guard matters because lastEscalation is carried forward across later non-escalated
        // rounds (GitTaskRepository#recordOutcome), so a Paused/Aborted/null outcome can still carry
        // a stale escalation report; those must "continue on the return alone" (FR9, D12), not be
        // steered back into the decision dialog.
        if (isEscalationDecision(outcome, bootstrap.lastEscalation())) {
            return decisionResume.resume(
                    cloneDir, bootstrap, definition, finalState, interactiveMode, tracker, ref, instanceId);
        }
        return resumeRunner.resumeWithoutDecision(
                cloneDir, bootstrap, definition, finalState, interactiveMode, discardWork, tracker, ref, instanceId);
    }

    /**
     * A recorded outcome is routed through the decision dialog only when it is a genuine
     * ESCALATION-kind park: an {@code Escalated} outcome whose report is {@link
     * EscalationReport.AttemptsExhausted} or {@link EscalationReport.DecisionNeeded} (design D3).
     */
    private static boolean isEscalationDecision(
            @Nullable TaskOutcomeDto outcome, @Nullable EscalationReport lastEscalation) {
        return outcome instanceof TaskOutcomeDto.Escalated
                && (lastEscalation instanceof EscalationReport.AttemptsExhausted
                        || lastEscalation instanceof EscalationReport.DecisionNeeded);
    }
}
