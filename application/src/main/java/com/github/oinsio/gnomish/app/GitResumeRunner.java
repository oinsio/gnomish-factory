package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.git.TaskWorktreePath;
import com.github.oinsio.gnomish.app.port.git.DeliveredBranchState;
import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Resume bootstrap (FR8, design D9) and outcome-driven continuation (task 4.7): the {@code
 * --resume} counterpart of {@link GitModeRunner}'s fresh-run wiring. {@link #bootstrap} locates
 * the task branch, materializes the worktree, reconciles local/origin divergence once (FR9,
 * NFR-R3) and loads/version-gates the {@code task.json} at the worktree's {@code HEAD}; {@link #run}
 * then switches on the recorded {@code outcome} (FR8), delegated to {@link GitResumeContinuation}:
 * {@code escalated} → the {@code --decision} resolved through {@link EscalationResume}; {@code
 * paused} → continue, no prompt; {@code null} → salvage (or {@code --discard-work}) and continue
 * from the recorded position (FR10, D10); {@code completed} → the delivered cleanup, if the tip
 * still owes it, then a report. A tip with no envelope that the classifier names {@code Delivered}
 * reports the task done from history and writes nothing (FR4 of fix-terminal-receipt-convergence).
 * UX2 is met by reusing the in-process machinery, so a resumed stop renders exactly as a live
 * run's.
 *
 * <p>The divergence reconciliation runs ahead of the outcome switch, so it applies to every
 * outcome alike. Its automatic discard of a truly diverged local line is the claim protocol's
 * arbitration, and {@code run --resume} holds no claim, so here a true divergence stops with
 * {@link com.github.oinsio.gnomish.app.port.git.DivergedBranchException} (exit 5) instead (FR8 of
 * harden-task-branch-contract).
 *
 * <p>Kept in sync with {@link ContainerResumeRunner}: both bootstrap a resumed task through the
 * same claimless reconciliation — {@code run --resume} holds no claim, so a truly diverged
 * local/origin pair fails closed with {@link
 * com.github.oinsio.gnomish.app.port.git.DivergedBranchException} rather than discarding the
 * local line — and both then dispatch on the branch's recorded {@code task.json} outcome over
 * one closed set, with the same meaning per arm — except that a recorded {@code AwaitingApproval}
 * position takes the {@code paused} arm whatever the outcome says (FR11 of
 * make-checkpoint-gate-durable): {@code null} salvages the interrupted round and
 * continues from the recorded position (honouring {@code --discard-work}), {@code escalated}
 * resolves the {@code --decision} through the same {@link EscalationResume}, {@code paused}
 * lands the approval commit and continues without a prompt, {@code completed} lands a cleanup still owed through
 * {@link DeliveredCleanup} and reports without another engine run, and {@code
 * aborted} refuses with a usage error naming the kept working copy; a {@code Delivered} tip —
 * named by {@link com.github.oinsio.gnomish.app.port.git.TaskBranchGit#classifyShape}, never by
 * a file probe — prints the {@link CompletedResume} report read from history and writes no
 * commit, and any other envelopeless tip stays an impossible state; both refuse a {@code
 * --decision} over a gate or a non-escalated outcome through {@link ResumeDecisionGuard} before any branch
 * write (FR9 of make-run-headless). Adding or re-meaning an arm on one side alone is the
 * divergence this pair guards against (UX2).
 *
 * <p>Implements FR5, FR8, FR9, FR10, NFR-R3, UX2 of add-git-workflow; FR9 of add-project-registry;
 * FR11 of make-checkpoint-gate-durable; FR4 of fix-terminal-receipt-convergence.
 */
final class GitResumeRunner {

    private final RunAssembly assembly;
    private final TaskGit git;
    private final RegisteredClone clone;
    private final TakeResumeBootstrap resumeBootstrap;

    /**
     * @param assembly the shared engine/ports assembly, reused from the fresh-run path — builds
     *     the same {@link com.github.oinsio.gnomish.app.console.DialogConsole} output path and {@link
     *     RunnerOutcomeLoop} a live run uses, so a resumed stop renders byte-for-byte the same (UX2)
     * @param git the task-git capability set: the run's repository and round persistence, branch
     *     lookup for the resume bootstrap, and salvage/materialization/cleanup
     * @param clone the registered clone whose own worktree folder the resumed worktree is
     *     materialized in (design D6; FR9 of add-project-registry)
     * @param taskIdMdcKey the MDC key to set to the branch's recorded taskId once bootstrap
     *     succeeds (design D9, task 8.2), matching the {@code bootstrap}-module {@code
     *     ManualRunRunner}'s own key
     */
    GitResumeRunner(RunAssembly assembly, TaskGit git, RegisteredClone clone, String taskIdMdcKey) {
        this.assembly = assembly;
        this.git = git;
        this.clone = clone;
        this.resumeBootstrap = new TakeResumeBootstrap(git, clone, taskIdMdcKey);
    }

    /**
     * Bootstraps the resumed task named by {@code taskId}, sets the {@code taskId} MDC key from
     * the branch's own recorded identity, then drives the continuation {@link #continueFrom} picks
     * (FR8), or the done report of a {@code Delivered} tip.
     *
     * <p>Implements FR5, FR8, UX2 of add-git-workflow; FR3, FR9 of make-run-headless.
     *
     * @param order the run order: the {@code --dir} clone (never mutated, FR7), the pipeline, the
     *     console mode and {@code --discard-work} (FR10, D10); its {@code base} is not read
     * @param taskId the {@code --resume} taskId, as supplied by the operator
     * @param decision the operator's {@code --decision}, or {@code null} when none was given (design
     *     D2 of make-run-headless)
     * @throws UsageException if no branch for {@code taskId} is found, or a {@code decision} was
     *     given over an outcome that is not escalated (FR9)
     */
    void run(RunOrder order, String taskId, @Nullable String decision) {
        Optional<ResumeBootstrap> loaded = bootstrap(order.cloneDir(), taskId);
        if (loaded.isPresent()) {
            continueFrom(order, loaded.get(), decision);
            return;
        }
        // No envelope at the reconciled tip: the classifier at the registered commit — the parse
        // every take route reads — names the one legal shape, a delivery whose cleanup landed (FR4
        // of fix-terminal-receipt-convergence); any other shape is the impossible state.
        if (!(git.branches().classifyShape(order.cloneDir(), taskId) instanceof BranchShape.Delivered)) {
            throw AbsentEnvelope.task(taskId, TaskWorktreePath.resolve(clone, taskId));
        }
        reportDelivered(order, taskId, decision);
    }

    /**
     * The {@code Delivered} route (FR4 of fix-terminal-receipt-convergence): the done report from
     * the {@code Completed} commit in history, and no branch write. The worktree the bootstrap just
     * materialized over the cleaned tip holds no task, so it is disposed again.
     */
    private void reportDelivered(RunOrder order, String taskId, @Nullable String decision) {
        DeliveredBranchState delivered =
                CompletedResume.readDelivered(git.branches(), order.cloneDir(), taskId, decision);
        MDC.put(resumeBootstrap.taskIdMdcKey(), delivered.context().taskId());
        git.worktrees().environmentDisposal(clone).dispose(TaskIdSanitizer.sanitize(taskId));
        CompletedResume.print(assembly, delivered.context(), delivered.finalState());
    }

    /**
     * Locates the task branch for {@code taskId} in {@code cloneDir}, materializes its worktree,
     * and loads its {@code task.json} into the handoff bundle {@link #continueFrom} switches on.
     *
     * <p>Implements FR8 of add-git-workflow.
     *
     * @param cloneDir the {@code --dir} project clone; never mutated (FR7 — only the worktree is
     *     written to)
     * @param taskId the {@code --resume} taskId, as supplied by the operator (un-sanitized)
     * @return the bootstrap bundle: located branch, materialized worktree, loaded task.json — or
     *     empty when the reconciled tip carries no task envelope, which {@link #run} classifies
     * @throws UsageException if no branch for {@code taskId} exists locally, as a remote-tracking
     *     ref, or on {@code origin} (even after the narrow fetch attempt)
     * @throws com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException if
     *     {@code task.json}'s {@code "version"} is missing or unsupported
     * @throws com.github.oinsio.gnomish.app.port.git.DivergedBranchException if the worktree's
     *     local branch tip and its {@code origin} remote-tracking tip share no ancestry
     *     relationship: {@code run --resume} holds no claim, so the reconciler cannot arbitrate
     *     the pair and fails closed (FR9; FR8 of harden-task-branch-contract)
     */
    Optional<ResumeBootstrap> bootstrap(Path cloneDir, String taskId) {
        return resumeBootstrap.bootstrap(cloneDir, taskId);
    }

    /**
     * Switches on {@code bootstrap.outcome()} (FR8, design D9) and drives the matching
     * continuation, delegated to {@link GitResumeContinuation}: {@code null} continues the engine
     * loop directly from {@code state.json}'s recorded position; {@code escalated} resolves the
     * decision first; {@code paused} continues; {@code completed} cleans a delivered tip still
     * carrying its envelope, reports and returns without another engine run. A decision over any
     * other outcome is refused here, before any arm writes.
     */
    private void continueFrom(RunOrder order, ResumeBootstrap bootstrap, @Nullable String decision) {
        Path cloneDir = order.cloneDir();
        var taskRepository = git.store().taskRepository(clone);
        TaskState finalState = git.store()
                .readRecordedState(bootstrap.worktreePath(), bootstrap.taskId())
                .orElseThrow(() -> AbsentEnvelope.state(bootstrap.taskId(), bootstrap.worktreePath()));

        RecordedOutcome outcome = bootstrap.outcome();
        ResumeDecisionGuard.requireEscalatedFor(bootstrap.taskId(), outcome, finalState.position(), decision);
        var continuation = new GitResumeContinuation(
                assembly, git, taskRepository, cloneDir, bootstrap, DeliveredCleanup.host(git, clone));
        if (finalState.position() instanceof Position.AwaitingApproval) {
            // FR11 of make-checkpoint-gate-durable: a gate is its own shape, whatever the outcome
            // says (a lost park, a landed park) — it takes the checkpoint continuation, never the
            // salvage-and-continue or decision arm.
            continuation.resumePaused(order, finalState);
            return;
        }
        if (outcome == null) {
            continuation.resumeFromRecordedPosition(order, finalState);
            return;
        }
        switch (outcome) {
            case RecordedOutcome.Completed ignored -> continuation.reportCompleted(finalState);
            case RecordedOutcome.Escalated ignored -> continuation.resumeEscalated(order, finalState, decision);
            case RecordedOutcome.Paused ignored -> continuation.resumePaused(order, finalState);
            case RecordedOutcome.Aborted ignored ->
                // An Aborted task.json means a prior visit's durability guarantee broke; nothing
                // to resume automatically. A plain usage error keeps the operator from building
                // on possibly-inconsistent state instead of silently continuing.
                throw new UsageException("cannot resume task \"" + bootstrap.taskId()
                        + "\": its last recorded outcome is Aborted — inspect the kept worktree at "
                        + bootstrap.worktreePath() + " and start a new task instead");
        }
    }
}
