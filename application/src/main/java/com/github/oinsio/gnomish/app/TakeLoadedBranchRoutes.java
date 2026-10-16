package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.DecisionAck;
import com.github.oinsio.gnomish.app.take.ParkLanding;
import com.github.oinsio.gnomish.app.take.ParkTransition;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * add-serve-sandbox-lifecycle; FR2, FR9, FR12 of harden-task-branch-contract; FR4, FR7, FR11 of
 * make-checkpoint-gate-durable; FR2 of fix-terminal-receipt-convergence.
 *
 * @param <B> the loaded-branch bundle {@code mechanics} produces
 * @param retry the bounded terminal-write retry every reconciled tracker write runs under — the
 *     slot wiring's, built on the composition root's time source (FR18 of
 *     supervise-daemon-loops-and-embed-dashboard)
 */
record TakeLoadedBranchRoutes<B extends ResumedBranch>(
        ResumeMechanics<B> mechanics, TakeDecisionResume<B> decisionResume, TaskGit git, TerminalWriteRetry retry) {

    private static final Logger log = LoggerFactory.getLogger(TakeLoadedBranchRoutes.class);

    /**
     * The loaded-branch routes: the shapes above all resume from what the branch itself records,
     * and the sub-state each one turns on — a still-set pending-write marker, the kind of park —
     * is read from the loaded bundle rather than from the shape.
     */
    TakeResult route(TakeOrder order) {
        // The one place the order is unpacked for the branch load: the bootstraps behind
        // loadBranch are shared with manual run --resume, which has no take order (FR5, design
        // Sync surfaces of introduce-take-order).
        Path cloneDir = order.run().cloneDir();
        String taskId = order.taskId();
        B branch = mechanics.loadBranch(cloneDir, taskId);
        if (branch == null) {
            // Reconcile-on-resume's Completed case (FR10, D10, NFR-C1 of add-claim-heartbeat): the
            // delivered outcome is durable in the branch and only the tracker write is missing — a
            // dead instance or a dead tracker at the finish line. Recover the delivered state from
            // branch history and post the deferred finish, exiting Delivered with zero engine rounds
            // rather than refusing or re-running paid work. The finish reuses TakeFinishReport + the
            // ClaimGuard pre-write check, so a reconcile that races a takeover cannot clobber a
            // successor. Needs no environment in either mode: it is a history read and a tracker write.
            return TakeReconcileFinish.deliverCompleted(git, order, retry);
        }
        TaskState finalState = mechanics.readFinalState(branch);

        // Reconcile-on-resume, the park case: an Escalated/Paused branch whose durable
        // "tracker-write pending" marker is still set recorded a park whose receipt never landed.
        // The marker alone cannot say whether the tracker write did: a kill between the write and
        // its receipt leaves the same branch as a write that never happened. The tracker's tenure
        // history can (FR2 of fix-terminal-receipt-convergence): a PARK boundary means the park
        // landed and a human has since returned the task, so the receipt is recorded and the run
        // falls through to the ordinary resume below, consuming the human's reply or approval
        // (design D6: roll forward, no re-drive). Otherwise the deferred park is completed and the
        // run exits — zero engine rounds, no paid gnome run.
        if (isOrphanedPark(branch)) {
            Runnable clearMarker = () -> mechanics.confirmTerminalWrite(cloneDir, branch);
            if (!ParkLanding.confirmFromHistory(order, clearMarker, log)) {
                // The same fence a fresh park runs (FR4, FR5 of fix-lifecycle-push): the resume-start
                // catch-up is best-effort, so an unreachable origin still shows in the re-posted park.
                return TakeReconcile.deliverPark(
                        branch,
                        finalState,
                        clearMarker,
                        order,
                        git.branches().fenceParkDelivery(cloneDir, taskId),
                        retry);
            }
        }

        // The CompletedUncleaned shape (FR9, FR10 of harden-task-branch-contract): the tip records
        // Completed while its envelope is still there, frozen by a kill between the outcome commit
        // and the tracker finish. Finish it — probe the tracker, re-drive the write only if it is
        // genuinely absent, then commit the cleanup — and never re-enter the engine: every stage
        // passed already, and re-running the last one would pay for delivered work (NFR-C1).
        if (branch.outcome() instanceof RecordedOutcome.Completed) {
            DeliveredCleanup cleanup = mechanics.deliveredCleanup(branch);
            return TakeReconcileFinish.finishUncleaned(
                    branch, finalState, () -> cleanup.cleanUp(cloneDir, branch.branch()), order, retry);
        }

        // The AwaitingApproval shape (FR4, FR11 of make-checkpoint-gate-durable): a manual stage
        // passed and its gate is closed. Checked before the escalation routing, since a gate's tip
        // may still carry a stale earlier outcome — which is not its park.
        if (finalState.position() instanceof Position.AwaitingApproval(String gate)) {
            return resumeAtGate(order, branch, finalState, gate);
        }

        // Route only a genuine ESCALATION-kind park through the decision dialog (design D3). The
        // outcome guard matters because lastEscalation is carried forward across later non-escalated
        // rounds (GitTaskRepository#recordOutcome), so a Paused/Aborted/null outcome can still carry
        // a stale escalation report; those must "continue on the return alone" (FR9, D12), not be
        // steered back into the decision dialog.
        if (isEscalationDecision(branch.outcome(), branch.lastEscalation())) {
            return decisionResume.resume(order, branch, finalState);
        }
        // FR12 of harden-task-branch-contract: the kill window between a decision commit and its
        // acknowledge. The branch carries the answer, the tracker still reports the reply as pending
        // — so the acknowledge is re-driven (upsert, no duplicate) and nothing else is repeated. Only
        // a branch that actually records decisions and no outcome — the Answered shape — pays the
        // read that detects it.
        redriveUnacknowledgedDecision(branch, order.tracker(), order.ref());

        // FR7 of make-checkpoint-gate-durable: an outcome still recorded here is continued without
        // a reply, so it is consumed by the resumed write before the engine runs — never left on
        // the tip under a live run.
        if (branch.outcome() != null) {
            return decisionResume.resumeReturned(order, branch, finalState);
        }
        return mechanics.resumeWithoutDecision(order, branch, finalState);
    }

    /**
     * A tip at a gate (FR4, FR11, design D2, D8 of make-checkpoint-gate-durable). When the gate's
     * own park is recorded — and, since an orphaned park was settled above, its marker cleared —
     * a human returned the checkpoint: the approval write opens the gate and the engine continues
     * from the approved state. Otherwise the park was lost before it was recorded (a kill between
     * the round commit and the park commit, or a stale earlier outcome on the tip): the park the
     * gate is owed is recorded and delivered, and the run exits without running a stage.
     */
    private TakeResult resumeAtGate(TakeOrder order, B branch, TaskState finalState, String gate) {
        if (branch.outcome() instanceof RecordedOutcome.Paused(String passed) && passed.equals(gate)) {
            TaskState approved = mechanics.approveCheckpoint(order, branch);
            return mechanics.resumeWithoutDecision(order, branch, approved);
        }
        var paused = new TaskOutcome.Paused(finalState, gate);
        // A fresh park (its intent is not on the branch yet), receipt included.
        var park = new ParkTransition.Fresh(
                () -> mechanics.recordPark(order, branch, paused),
                () -> mechanics.confirmTerminalWrite(order.run().cloneDir(), branch));
        return TakePauseExit.finish(paused, branch.context(), branch.branchName(), order, retry, park);
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
     * A just-claimed branch carries a park whose receipt is owed when its durable "tracker-write
     * pending" marker is still set AND its recorded outcome is a park ({@code Escalated}/{@code
     * Paused}) (FR10, D10, NFR-C1); whether the tracker write itself landed is the tracker's
     * history to say. Package-private so the guard is unit-tested over the pending/outcome matrix.
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
