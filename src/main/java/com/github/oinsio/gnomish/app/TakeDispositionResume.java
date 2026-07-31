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
 * un-takeable and break the K-fuse retry loop.
 *
 * <p>A delivered-and-cleaned-up branch whose tracker finish never landed (task.json AND state.json
 * already {@code git rm}'d on {@code Completed}, FR15, so bootstrap raises {@link
 * NoSuchFileException}) is no longer refused: it is reconcile-on-resume's {@code Completed} case
 * (FR10, D10, NFR-C1 of add-claim-heartbeat). The delivered outcome is durable in the branch and
 * only the tracker write is missing (a dead instance or a dead tracker at the finish line), so the
 * run recovers the delivered state from branch history and posts the deferred finish via {@link
 * TakeReconcile#deliverCompleted}, exiting {@code Delivered} with zero engine rounds instead of
 * demanding a human reconcile labels by hand.
 *
 * <p>Split out of {@link TakeDisposition} purely to respect the file-size guidance
 * (`.claude/rules/process-invariants.md`).
 *
 * <p>Implements FR9, D3 of add-tracker-port; FR10, D10, NFR-C1 of add-claim-heartbeat.
 */
record TakeDispositionResume(TakeResumeRunner resumeRunner, TakeDecisionResume decisionResume, Path worktreesRoot) {

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
                // The branch's own cleanup commit (GitTaskRepository#recordOutcome on Completed,
                // FR15) already removed .gnomish-task/ from the tip entirely (task.json AND
                // state.json both gone, in the same commit): the branch recorded delivery but the
                // tracker never got the finish — a dead instance or a dead tracker at the finish
                // line. Reconcile-on-resume's Completed case (FR10, D10, NFR-C1 of
                // add-claim-heartbeat): recover the delivered state from branch history and post the
                // deferred finish, exiting Delivered with zero engine rounds rather than refusing or
                // re-running paid work. The finish reuses TakeFinishReport + the ClaimGuard pre-write
                // check (task 6.3), so a reconcile that races a takeover cannot clobber a successor.
                return TakeReconcile.deliverCompleted(cloneDir, taskId, tracker, ref, instanceId);
            }
            throw e;
        }
        TaskState finalState = GitFreshTaskSupport.readFinalState(bootstrap.worktreePath());
        TaskOutcomeDto outcome = bootstrap.outcome();

        // Reconcile-on-resume, the park case (FR10, D10, NFR-C1 of add-claim-heartbeat): an
        // Escalated/Paused branch whose durable "tracker-write pending" marker is still set means its
        // park write never landed (a dead instance/tracker at the park line, then a reaper returned
        // the stale claim to Ready). Complete the deferred park and exit — zero engine rounds, no
        // paid gnome run — while a cleared marker (the park did land, e.g. a human answered and
        // returned the task) falls through to the ordinary resume below. This is exactly the
        // distinction the tracker alone cannot make post-claim (it always shows Working held by us).
        if (isOrphanedPark(bootstrap)) {
            return TakeReconcile.deliverPark(cloneDir, worktreesRoot, bootstrap, tracker, ref, instanceId);
        }

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
     * A just-claimed branch is an ORPHANED park to reconcile (deferred park, zero engine rounds) when
     * its durable "tracker-write pending" marker is still set AND its recorded outcome is a park
     * ({@code Escalated}/{@code Paused}) — the park write never landed before the holder died (FR10,
     * D10, NFR-C1). A cleared marker, or any non-park outcome, is not reconciled here.
     *
     * <p>Package-private (not private) so the guard is unit-testable directly over the
     * pending/outcome matrix — the branch reroutes to the git-backed resume path when negated,
     * which a fast unit test over this predicate pins deterministically rather than a slow
     * git-backed lifecycle spec.
     */
    static boolean isOrphanedPark(ResumeBootstrap bootstrap) {
        return bootstrap.trackerWritePending()
                && (bootstrap.outcome() instanceof TaskOutcomeDto.Escalated
                        || bootstrap.outcome() instanceof TaskOutcomeDto.Paused);
    }

    /**
     * A recorded outcome is routed through the decision dialog only when it is a genuine
     * ESCALATION-kind park: an {@code Escalated} outcome whose report is {@link
     * EscalationReport.AttemptsExhausted} or {@link EscalationReport.DecisionNeeded} (design D3).
     *
     * <p>Package-private (not private) so the routing predicate is unit-testable directly over the
     * full outcome/report matrix, without standing up the git-backed resume collaborators.
     */
    static boolean isEscalationDecision(@Nullable TaskOutcomeDto outcome, @Nullable EscalationReport lastEscalation) {
        return outcome instanceof TaskOutcomeDto.Escalated
                && (lastEscalation instanceof EscalationReport.AttemptsExhausted
                        || lastEscalation instanceof EscalationReport.DecisionNeeded);
    }
}
