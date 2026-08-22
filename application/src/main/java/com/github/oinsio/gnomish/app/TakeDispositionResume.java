package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The "branch already exists" half of {@link TakeDisposition}'s {@code Ready} case (FR9, D3): the
 * ONE routing table a just-claimed existing branch is dispatched through, in host and container
 * mode alike (design D8 of add-serve-sandbox-lifecycle). Four routes, decided from the branch's own
 * recorded facts and never from the tracker (which post-claim always reports {@code Working} held
 * by us):
 *
 * <ul>
 *   <li>the branch delivered but its finish never landed — {@link TakeReconcile#deliverCompleted},
 *       zero engine rounds
 *   <li>the branch parked but its park write never landed — {@link TakeReconcile#deliverPark}, zero
 *       engine rounds
 *   <li>an ESCALATION-kind park — the decision dialog, {@link TakeDecisionResume}
 *   <li>anything else ({@code null} outcome, {@code Paused}, an INFRA-kind {@code Escalated},
 *       {@code Aborted}) — resume on the human's return alone (FR9, D12)
 * </ul>
 *
 * <p>{@code Aborted} is deliberately NOT refused here (contrast manual-run {@link
 * GitResumeRunner}, which refuses it): the abort protocol (FR14) intentionally returns a below-K
 * abort to {@code Ready} so a later claim retries it from the last durably recorded {@code
 * state.json} position. Refusing would strand every below-K abort as permanently un-takeable and
 * break the K-fuse retry loop.
 *
 * <p>Everything mode-specific — worktree versus box, where {@code state.json} is read, whether a
 * "tracker-write pending" marker can be cleared at all — is reached through {@link ResumeMechanics}.
 *
 * <p>Implements FR9, D3 of add-tracker-port; FR10, D10, NFR-C1 of add-claim-heartbeat; FR1 of
 * add-serve-sandbox-lifecycle.
 *
 * @param <B> the loaded-branch bundle {@code mechanics} produces
 */
record TakeDispositionResume<B extends ResumedBranch>(
        ResumeMechanics<B> mechanics, TakeDecisionResume<B> decisionResume, TaskGit git) {

    /**
     * Loads the existing branch for {@code taskId} and dispatches on its recorded facts (see class
     * javadoc).
     *
     * <p>Implements FR9, D3 of add-tracker-port; FR1 of add-serve-sandbox-lifecycle.
     */
    TakeResult resumeExisting(
            Path cloneDir,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            String taskId,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        B branch = mechanics.loadBranch(cloneDir, taskId);
        if (branch == null) {
            // Reconcile-on-resume's Completed case (FR10, D10, NFR-C1 of add-claim-heartbeat): the
            // delivered outcome is durable in the branch and only the tracker write is missing — a
            // dead instance or a dead tracker at the finish line. Recover the delivered state from
            // branch history and post the deferred finish, exiting Delivered with zero engine rounds
            // rather than refusing or re-running paid work. The finish reuses TakeFinishReport + the
            // ClaimGuard pre-write check, so a reconcile that races a takeover cannot clobber a
            // successor. Needs no environment in either mode: it is a history read and a tracker write.
            return TakeReconcile.deliverCompleted(git, cloneDir, taskId, tracker, ref, instanceId);
        }
        TaskState finalState = mechanics.readFinalState(branch);

        // Reconcile-on-resume, the park case: an Escalated/Paused branch whose durable
        // "tracker-write pending" marker is still set means its park write never landed (a dead
        // instance/tracker at the park line, then a reaper returned the stale claim to Ready).
        // Complete the deferred park and exit — zero engine rounds, no paid gnome run — while a
        // cleared marker (the park did land, e.g. a human answered and returned the task) falls
        // through to the ordinary resume below. This is exactly the distinction the tracker alone
        // cannot make post-claim.
        if (isOrphanedPark(branch)) {
            return TakeReconcile.deliverPark(
                    branch,
                    finalState,
                    () -> mechanics.confirmTerminalWrite(cloneDir, branch),
                    tracker,
                    ref,
                    instanceId);
        }

        // Route only a genuine ESCALATION-kind park through the decision dialog (design D3). The
        // outcome guard matters because lastEscalation is carried forward across later non-escalated
        // rounds (GitTaskRepository#recordOutcome), so a Paused/Aborted/null outcome can still carry
        // a stale escalation report; those must "continue on the return alone" (FR9, D12), not be
        // steered back into the decision dialog.
        if (isEscalationDecision(branch.outcome(), branch.lastEscalation())) {
            return decisionResume.resume(cloneDir, branch, finalState, interactiveMode, tracker, ref, instanceId);
        }
        return mechanics.resumeWithoutDecision(
                cloneDir, branch, finalState, interactiveMode, discardWork, tracker, ref, instanceId);
    }

    /**
     * A just-claimed branch is an ORPHANED park to reconcile (deferred park, zero engine rounds)
     * when its durable "tracker-write pending" marker is still set AND its recorded outcome is a
     * park ({@code Escalated}/{@code Paused}) — the park write never landed before the holder died
     * (FR10, D10, NFR-C1). A cleared marker, or any non-park outcome, is not reconciled here.
     *
     * <p>Package-private (not private) so the guard is unit-testable directly over the
     * pending/outcome matrix — the branch reroutes to the ordinary resume path when negated, which
     * a fast unit test over this predicate pins deterministically rather than a slow lifecycle spec.
     */
    static boolean isOrphanedPark(ResumedBranch branch) {
        return branch.trackerWritePending()
                && (branch.outcome() instanceof RecordedOutcome.Escalated
                        || branch.outcome() instanceof RecordedOutcome.Paused);
    }

    /**
     * A recorded outcome is routed through the decision dialog only when it is a genuine
     * ESCALATION-kind park: an {@code Escalated} outcome whose report is {@link
     * EscalationReport.AttemptsExhausted} or {@link EscalationReport.DecisionNeeded} (design D3).
     *
     * <p>Package-private (not private) so the routing predicate is unit-testable directly over the
     * full outcome/report matrix, without standing up the resume collaborators.
     */
    static boolean isEscalationDecision(@Nullable RecordedOutcome outcome, @Nullable EscalationReport lastEscalation) {
        return outcome instanceof RecordedOutcome.Escalated
                && (lastEscalation instanceof EscalationReport.AttemptsExhausted
                        || lastEscalation instanceof EscalationReport.DecisionNeeded);
    }
}
