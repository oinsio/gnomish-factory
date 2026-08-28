package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.DoNotMutate;
import com.github.oinsio.gnomish.app.port.git.ParkDeliveryVerdict;
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.ParkTransition;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.app.take.TerminalWriteRetry;
import com.github.oinsio.gnomish.domain.engine.EscalationReport;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import org.jspecify.annotations.Nullable;

/**
 * Reconcile-on-resume at the head of the claim path (FR10, D10, NFR-C1): when a just-claimed task's
 * branch records a terminal outcome whose tracker counterpart never landed, complete the deferred
 * tracker write and end — running zero engine rounds, so a dead tracker or a dead instance costs
 * bookkeeping, never a repeated (paid) gnome run.
 *
 * <p>Only the {@code Completed} -> deferred-finish case is reconciled here, and it is the M4
 * deliverable. It is the one branch outcome that is unambiguously terminal: a {@code Completed} task
 * has no legitimate resume, so a branch that recorded {@code Completed} while the tracker is still
 * open (anything but {@code Finished}) always means "the finish write is pending", never "re-run".
 * The {@code Completed} cleanup commit (FR15 of add-git-workflow) removed {@code .gnomish-task/}
 * from the branch tip, so {@link ResumeMechanics#loadBranch} finds no live {@code task.json} and
 * reports the branch as delivered-and-cleaned; the delivered state is recovered from
 * branch history via the {@link TaskBranchGit} port and posted through the very same {@link
 * TakeFinishReport#finish} a fresh completion uses — identical report text, identical {@link
 * TakeResult.Delivered}, identical {@link com.github.oinsio.gnomish.app.take.ClaimGuard} "claim
 * still ours" pre-write guard (task 6.3). The guard is what makes the deferred write safe against a
 * racing takeover: if the claim moved on (e.g. the task is already {@code Finished}, or a successor
 * now holds it), the finish is skipped rather than clobbering the successor's state, and the run
 * still returns {@code Delivered} (the branch already carries the delivered outcome).
 *
 * <p>The {@code Escalated}/{@code Paused} -> deferred-park cases are reconciled by {@link
 * #deliverPark} (task 6.5). {@code resumeExisting} runs only after {@code tracker.claim} has already
 * succeeded, so the tracker always reports {@code Working} held by this instance at this point and
 * cannot itself tell an orphaned park (the park write never landed → the claim went stale → a reaper
 * returned it to {@code Ready} → it was re-claimed) from a legitimate human-returned park (the park
 * landed → the human answered and returned it to {@code Ready}). The durable "tracker-write pending"
 * marker on the branch ({@code task.json}, {@link
 * com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore#confirmTerminalWrite}) is exactly the
 * "counterpart landed" signal the tracker lacks: {@code recordOutcome} sets it when a park is
 * recorded and clears it once the park write confirms, so at resume an {@code Escalated}/{@code
 * Paused} branch whose marker is still set means "the park never landed" (fire the deferred park,
 * zero engine rounds), and a cleared marker means "the park landed" (ordinary resume proceeds,
 * including the human's decision). A {@code DecisionNeeded} the human already answered has a cleared
 * marker, so it resumes through {@link TakeDecisionResume} rather than being wrongly re-parked (see
 * {@code InMemoryTakeLifecycleEscalateResumeSpec}).
 *
 * <p>Implements FR10, D10, NFR-C1 of add-claim-heartbeat.
 */
final class TakeReconcile {

    private TakeReconcile() {}

    /**
     * Posts the deferred park for an {@code Escalated}/{@code Paused} branch whose "tracker-write
     * pending" marker is still set (the park write never landed), running no engine round: the park
     * is reconstructed from the branch's own recorded outcome ({@code task.json}) and final state
     * ({@code state.json}) and re-sent through the same {@link TakeEscalationExit}/{@link
     * TakePauseExit} exit a fresh park uses — including the {@link
     * com.github.oinsio.gnomish.app.take.ClaimGuard} pre-write guard, the bounded {@link
     * TerminalWriteRetry}, and the marker-clearing {@code confirmTerminalWrite} on confirmation — so
     * a reconcile that races a takeover cannot clobber a successor (FR10, D10, NFR-C1).
     *
     * <p>Implements FR10, D10, NFR-C1 of add-claim-heartbeat.
     *
     * <p>Mode-independent (design D8 of add-serve-sandbox-lifecycle): the park is rebuilt from the
     * branch's own facts and the tracker write is the whole action, so host and container mode
     * differ only in where {@code finalState} was read from and how the marker is cleared — both
     * modes do clear it now (FR10, D12 of harden-task-branch-contract), so a re-delivered park
     * settles instead of returning on every later resume.
     *
     * @param branch the resumed branch: recorded park outcome, escalation report, branch name
     * @param finalState the branch's last durably recorded state, read by the caller's mechanics
     * @param clearMarker clears the durable "tracker-write pending" marker once the write confirms
     * @param tracker the tracker port the deferred park is made through; never null
     * @param ref the task's tracker identity; never null
     * @param instanceId this factory instance's identity, for the pre-write claim check; never null
     * @param parkDelivery the caller's delivery-fence verdict for this branch (FR4, FR5 of
     *     fix-lifecycle-push). The resume-start touchpoint has already tried to bring origin up to
     *     the tip on the way in, but that catch-up is best-effort and swallows its failure — so the
     *     fence still runs before this write, and an {@code Undelivered} verdict puts the
     *     origin-behind line in the re-posted park report exactly as a fresh park does. A caller
     *     with no fence to run (container mode, whose pushes are best-effort throughout) passes
     *     {@link ParkDeliveryVerdict.Delivered}
     * @return the {@link TakeResult.AwaitingHuman} the deferred park produced; never null
     */
    static TakeResult deliverPark(
            ResumedBranch branch,
            TaskState finalState,
            Runnable clearMarker,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId,
            ParkDeliveryVerdict parkDelivery) {
        var retry = TerminalWriteRetry.system();
        // A recovered park: its intent is already on the branch, so the protocol probes the tracker
        // before re-driving the write and adds no duplicate artifact (FR10).
        var transition = new ParkTransition.Recovered(parkDelivery, clearMarker);
        if (branch.outcome() instanceof RecordedOutcome.Paused(var passedStage)) {
            var pausedOutcome = new TaskOutcome.Paused(finalState, passedStage);
            return TakePauseExit.finish(
                    pausedOutcome, branch.context(), branch.branchName(), tracker, ref, instanceId, retry, transition);
        }
        // The only remaining park kind is Escalated: resumeExisting calls deliverPark solely for an
        // Escalated/Paused branch, and recordOutcome always records lastEscalation alongside an
        // Escalated outcome — so the report is present. The instanceof pattern above already handles
        // a null outcome (it simply doesn't match), so no explicit null case is needed here.
        var escalated = new TaskOutcome.Escalated(finalState, requireEscalationReport(branch.lastEscalation()));
        return TakeEscalationExit.exit(escalated, tracker, ref, instanceId, retry, transition);
    }

    // PIT M4 documented exception: @DoNotMutate — the null branch is provably unreachable on the
    // deliverPark path (an Escalated branch always carries its escalation report; see
    // TaskRepository#recordOutcome), so no unit test can drive report == null here; the non-null
    // path is exercised by the deferred-park reconcile lifecycle spec. Isolated to its own method so
    // this defensive check has nowhere to hide as a false SURVIVED against the reconcile logic.
    @DoNotMutate
    private static EscalationReport requireEscalationReport(@Nullable EscalationReport report) {
        if (report == null) {
            throw new IllegalStateException("an Escalated park branch must carry its escalation report");
        }
        return report;
    }
}
