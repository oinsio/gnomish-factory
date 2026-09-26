package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Resume wiring for {@code take} over the existing git-protocol machinery (design D3, FR9,
 * FR12): reuses {@code TaskBranchLocator}/{@code TaskWorktreeManager}/{@code
 * ReplicaPairReconciler} (adapter-module collaborators, reached only through the {@link TaskGit}
 * port from here) for {@link #bootstrap} — the same branch-locate/narrow-fetch/
 * worktree-materialize/divergence-reconcile steps {@link GitResumeRunner#bootstrap} performs for
 * manual-run {@code --resume} — then drives the engine directly through {@link
 * TakeEngineExecution} instead of {@link RunnerOutcomeLoop}: {@code take} never opens a console
 * dialog (design D12).
 *
 * <p>Two resume entry points mirror the two shapes a park can be resumed from (design D3): {@link
 * #resumeWithoutDecision} for a {@code null}/{@code CHECKPOINT}/{@code INFRA} return, no decision
 * involved; {@link #resumeDecided} for an {@code ESCALATION} return, from a context the caller's
 * {@link #appendDecision} has already committed when there was a reply to commit — mirroring {@code
 * EscalationResumeDialog#handleResumable}'s exact reset formula.
 *
 * <p>Kept in sync with {@link TakeContainerResumeRunner}: both resolve the resumed law binding
 * through {@link ResumeLawBinding} (pinned-ref tip resolution) before building their execution
 * tail — the current tip of the pinned base ref, narrow-fetched or read locally, parking or
 * releasing the claim exactly alike on the two failure branches.
 *
 * <p>Implements FR9, FR12, D3 of add-tracker-port; FR12, D13 of add-base-ref-resolution.
 */
final class TakeResumeRunner {

    private final TaskGit git;
    private final Path worktreesRoot;
    private final TakeResumeBootstrap resumeBootstrap;
    private final TakeResumeExecution execution;

    /**
     * @param wiring the slot's equipment (D2 of introduce-slot-wiring): the shared engine/ports
     *     assembly reused from the manual-run path (the same {@link
     *     com.github.oinsio.gnomish.domain.engine.EnginePorts} bundle a live run uses, minus the
     *     dialog console take never opens); the task-git capability set the resumed run's store,
     *     branch and worktree operations come from; the worktrees root (design D6); the MDC key set
     *     to the branch's recorded taskId once bootstrap succeeds, matching {@link
     *     GitResumeRunner}'s own key; the abort fuse applied when a resumed engine run returns
     *     {@code Aborted} (task 5.3); the tracker adapter's declared credential variable names
     *     (design D17, NFR-S1 of add-tracker-port); and the tenure whose claim-loss flag makes the
     *     round boundary react to a beat-detected loss as a revocation (task 6.3, FR8 of
     *     add-claim-heartbeat) — all forwarded to {@link TakeResumeExecution}
     */
    TakeResumeRunner(SlotWiring wiring) {
        this.git = wiring.git();
        this.worktreesRoot = wiring.worktreesRoot();
        this.resumeBootstrap = new TakeResumeBootstrap(git, worktreesRoot, wiring.taskIdMdcKey());
        this.execution = new TakeResumeExecution(wiring);
    }

    /**
     * Locates the task branch for {@code taskId} in {@code cloneDir}, materializes its worktree,
     * reconciles local/origin divergence, and loads its {@code task.json} — delegated to {@link
     * TakeResumeBootstrap}, the same steps {@link GitResumeRunner#bootstrap} performs, reused
     * rather than reimplemented.
     *
     * <p>Implements FR9 of add-tracker-port.
     *
     * @param cloneDir the project clone; never mutated
     * @param taskId the tracker's original taskId, as supplied to {@code take --resume}
     * @return the bootstrap bundle, or {@code null} when the branch tip carries no task envelope —
     *     the delivered shape, which {@link HostResumeMechanics#loadBranch} routes on (design D1,
     *     D2 of fix-envelope-medium)
     * @throws UsageException if no branch for {@code taskId} is found
     */
    public @Nullable ResumeBootstrap bootstrap(Path cloneDir, String taskId) {
        return resumeBootstrap.bootstrap(cloneDir, taskId).orElse(null);
    }

    /**
     * Resumes a {@code null} (process died mid-visit), {@code CHECKPOINT}, or {@code INFRA} park —
     * none needs the attempt-counter reset, since none burned an attempt. Salvages (default) or
     * discards ({@code --discard-work}) the interrupted round's leftovers exactly as {@link
     * GitResumeContinuation#resumeFromRecordedPosition} does, then runs the engine once.
     *
     * <p>Implements FR9 of add-tracker-port.
     *
     * @param order the take order being resumed: the clone (never mutated), the pipeline the run
     *     advances through, the interactive mode, {@code --discard-work} (true discards
     *     interrupted leftovers instead of salvaging them), and the tracker, task identity and
     *     instance identity for the revocation check wrapped around persistence
     * @param bootstrap the located/materialized bundle from {@link #bootstrap}
     * @param finalState the state to resume from, unchanged from the park
     * @return the mapped {@link TakeResult} for the engine run
     */
    public TakeResult resumeWithoutDecision(TakeOrder order, ResumeBootstrap bootstrap, TaskState finalState) {
        var salvage = git.worktrees().salvage(bootstrap.worktreePath());
        if (order.run().discardWork()) {
            salvage.discard();
        } else {
            salvage.salvage(bootstrap.taskId());
        }

        return execution.run(
                order.run().cloneDir(),
                ResumeLawBinding.pinnedRef(bootstrap.pin(), bootstrap.baseCommit()),
                finalState,
                order.ref(),
                order.tracker(),
                eng -> eng.run(order, bootstrap, bootstrap.context(), finalState));
    }

    /**
     * Resumes an {@code ESCALATION} park ({@code AttemptsExhausted} or {@code DecisionNeeded}):
     * resets {@code attemptsUsed} to 0 with an empty attempt history — {@code
     * EscalationResumeDialog#handleResumable}'s formula — then runs the engine once. The already
     * -collected human reply, when non-blank, is appended by the caller before this is invoked
     * (design D12); an {@code AttemptsExhausted} park may resume on the return alone.
     *
     * <p>Implements FR9, FR12, D3, D12 of add-tracker-port. Parameters shared with {@link
     * #resumeWithoutDecision} carry the same meaning there; only the ones specific to a decided
     * resume are documented here.
     *
     * @param context the task context the run continues from — the human's decision included when
     *     one was committed, the branch's own otherwise (design D12)
     * @param resetState the escalated state with its attempt counter reset
     * @return the mapped {@link TakeResult} for the engine run
     */
    public TakeResult resumeDecided(
            TakeOrder order, ResumeBootstrap bootstrap, TaskContext context, TaskState resetState) {
        return execution.run(
                order.run().cloneDir(),
                ResumeLawBinding.pinnedRef(bootstrap.pin(), bootstrap.baseCommit()),
                resetState,
                order.ref(),
                order.tracker(),
                eng -> eng.run(order, bootstrap, context, resetState));
    }

    /**
     * Commits the human's decision to the branch — the durable intent the tracker acknowledge
     * follows (FR12 of harden-task-branch-contract), landing in one commit with the attempt-counter
     * reset it implies (FR4).
     */
    TaskContext appendDecision(
            Path cloneDir, ResumeBootstrap bootstrap, TaskState finalState, TaskState resetState, String text) {
        var taskRepository = git.store().taskRepository(cloneDir, worktreesRoot);
        var decision = ResumeDecisionCommit.decisionFor(finalState, text);
        taskRepository.appendDecision(bootstrap.taskId(), decision, resetState);
        return ResumeDecisionCommit.appendTo(bootstrap.context(), decision);
    }
}
