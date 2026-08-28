package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.DecisionAck;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The routes a resumable branch takes once it is loaded — the second half of {@link
 * TakeDispositionResume}'s table, split out to keep both files inside the project's file-size
 * guidance. The first half decides on the branch's {@linkplain
 * com.github.oinsio.gnomish.domain.branch.BranchShape shape}; this one decides on the sub-state
 * the shape deliberately does not carry: whether a park's pending-write marker is still set, and
 * which kind of park it is.
 *
 * <p>{@code Aborted} is deliberately NOT refused here (contrast manual-run {@link
 * GitResumeRunner}, which refuses it): the abort protocol (FR14) intentionally returns a below-K
 * abort to {@code Ready} so a later claim retries it from the last durably recorded {@code
 * state.json} position. Refusing would strand every below-K abort as permanently un-takeable and
 * break the K-fuse retry loop.
 *
 * <p>Implements FR9, D3 of add-tracker-port; FR10, D10, NFR-C1 of add-claim-heartbeat; FR1 of
 * add-serve-sandbox-lifecycle; FR2, FR9, FR12 of harden-task-branch-contract.
 *
 * @param <B> the loaded-branch bundle {@code mechanics} produces
 */
record TakeLoadedBranchRoutes<B extends ResumedBranch>(
        ResumeMechanics<B> mechanics, TakeDecisionResume<B> decisionResume, TaskGit git) {

    /**
     * The loaded-branch routes: the shapes above all resume from what the branch itself records,
     * and the sub-state each one turns on — a still-set pending-write marker, the kind of park —
     * is read from the loaded bundle rather than from the shape.
     */
    TakeResult route(
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
            return TakeReconcileFinish.deliverCompleted(git, cloneDir, taskId, tracker, ref, instanceId);
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
            // The same fence a fresh park runs (FR4, FR5 of fix-lifecycle-push). The resume-start
            // touchpoint already tried to bring origin up to this tip, but that catch-up is
            // best-effort and swallows its own failure — so when origin is genuinely unreachable
            // the re-posted park carries the origin-behind line instead of reading as replicated.
            // An origin that does hold the tip costs one refs read and nothing else.
            return TakeReconcile.deliverPark(
                    branch,
                    finalState,
                    () -> mechanics.confirmTerminalWrite(cloneDir, branch),
                    tracker,
                    ref,
                    instanceId,
                    git.branches().fenceParkDelivery(cloneDir, taskId));
        }

        // The CompletedUncleaned shape (FR9, FR10 of harden-task-branch-contract): the tip records
        // Completed while its envelope is still there, frozen by a kill between the outcome commit
        // and the tracker finish. Finish it — probe the tracker, re-drive the write only if it is
        // genuinely absent, then commit the cleanup — and never re-enter the engine: every stage
        // passed already, and re-running the last one would pay for delivered work (NFR-C1).
        if (branch.outcome() instanceof RecordedOutcome.Completed) {
            return TakeReconcileFinish.finishUncleaned(
                    branch, finalState, () -> mechanics.finishCleanup(cloneDir, branch), tracker, ref, instanceId);
        }

        // Route only a genuine ESCALATION-kind park through the decision dialog (design D3). The
        // outcome guard matters because lastEscalation is carried forward across later non-escalated
        // rounds (GitTaskRepository#recordOutcome), so a Paused/Aborted/null outcome can still carry
        // a stale escalation report; those must "continue on the return alone" (FR9, D12), not be
        // steered back into the decision dialog.
        if (isEscalationDecision(branch.outcome(), branch.lastEscalation())) {
            return decisionResume.resume(cloneDir, branch, finalState, interactiveMode, tracker, ref, instanceId);
        }
        // FR12 of harden-task-branch-contract: the kill window between a decision commit and its
        // acknowledge. The branch carries the answer, the tracker still reports the reply as pending
        // — so the acknowledge is re-driven (upsert, no duplicate) and nothing else is repeated. Only
        // a branch that actually records decisions and no outcome — the Answered shape — pays the
        // read that detects it.
        redriveUnacknowledgedDecision(branch, tracker, ref);

        return mechanics.resumeWithoutDecision(
                cloneDir, branch, finalState, interactiveMode, discardWork, tracker, ref, instanceId);
    }

    /**
     * Re-drives the acknowledge of a decision already durable on the branch, if one is owed (FR12).
     *
     * <p>Implements FR12 of harden-task-branch-contract.
     */
    private static void redriveUnacknowledgedDecision(ResumedBranch branch, Tracker tracker, TaskRef ref) {
        if (branch.outcome() != null || branch.context().decisions().isEmpty()) {
            return;
        }
        String owed = DecisionAck.unacknowledged(tracker.collectDecisions(ref), branch.context());
        if (owed != null) {
            DecisionAck.redriveAcknowledge(tracker, ref, branch.context(), owed);
        }
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
