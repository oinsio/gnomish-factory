package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.RecordedOutcome;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;

/**
 * Resume bootstrap (FR8, design D9) and outcome-driven continuation (task 4.7): the {@code
 * --resume} counterpart of {@link GitModeRunner}'s fresh-run wiring. {@link #bootstrap} locates
 * the task branch, materializes the worktree, and loads/version-gates {@code task.json}; {@link
 * #run} then switches on the recorded {@code outcome} (FR8), delegated to {@link
 * GitResumeContinuation}: {@code escalated} → the same decision dialog the in-process path uses;
 * {@code paused} → the same checkpoint confirmation; {@code null} (process died mid-visit) →
 * continue the engine loop straight from the recorded {@code state.json} position, no dialog;
 * {@code completed} → print a report and return without touching the worktree or branch again.
 *
 * <p>UX2 is met by reusing the exact in-process dialog machinery ({@link
 * EscalationResumeDialog#handle}, the same {@link RunAssembly}-built console) rather than a
 * parallel implementation, so prompts and EOF handling match a live run's.
 *
 * <p>{@link #bootstrap} also reconciles local/origin divergence (FR9, NFR-R3, design D9) once,
 * right after the worktree is materialized and before {@code task.json} is read back — equal or
 * ahead leave the worktree untouched; behind fast-forwards it and discards uncommitted leftovers
 * (a {@link com.github.oinsio.gnomish.adapter.git.WorktreeDivergenceCheck}); diverged throws
 * {@link com.github.oinsio.gnomish.app.port.git.DivergedBranchException}. Running this once in
 * {@code bootstrap}, ahead of the outcome switch, applies it uniformly to every resume outcome
 * (null/escalated/paused/completed alike), since divergence is a general resume precondition, not
 * specific to one outcome.
 *
 * <p>Salvage of an interrupted round's uncommitted leftovers and {@code --discard-work} (FR10,
 * design D10) are the {@code null}-outcome continuation's concern instead, since divergence
 * reconciliation only handles history that diverged from origin — not leftovers still sitting
 * uncommitted on top of an otherwise-reconciled worktree; see {@link
 * GitResumeContinuation#resumeFromRecordedPosition}.
 *
 * <p>Implements FR5, FR8, FR9, FR10, NFR-R3, UX2 of add-git-workflow.
 */
final class GitResumeRunner {

    private final RunAssembly assembly;
    private final TaskGit git;
    private final Path worktreesRoot;
    private final TakeResumeBootstrap resumeBootstrap;

    /**
     * @param assembly the shared engine/ports assembly, reused from the fresh-run path — builds
     *     the same {@link com.github.oinsio.gnomish.app.console.DialogConsole} and {@link
     *     RunnerOutcomeLoop} a live run uses, so resume dialogs are byte-for-byte the same (UX2)
     * @param git the task-git capability set: the run's repository and round persistence, branch
     *     lookup for the resume bootstrap, and salvage/materialization/cleanup
     * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
     *     worktrees are created (design D6); production wiring resolves {@code
     *     ~/.gnomish/worktrees}, tests pass a temp directory
     * @param taskIdMdcKey the MDC key to set to the branch's recorded taskId once bootstrap
     *     succeeds (design D9, task 8.2), matching {@link ManualRunRunner}'s own key
     */
    GitResumeRunner(RunAssembly assembly, TaskGit git, Path worktreesRoot, String taskIdMdcKey) {
        this.assembly = assembly;
        this.git = git;
        this.worktreesRoot = worktreesRoot;
        this.resumeBootstrap = new TakeResumeBootstrap(git, worktreesRoot, taskIdMdcKey);
    }

    /**
     * Bootstraps the resumed task named by {@code taskId}, sets the {@code taskId} MDC key from
     * the branch's own recorded identity, then drives the outcome-driven continuation (FR8): the
     * decision dialog, the checkpoint confirmation, the direct continuation, or the completion
     * report, per {@link #continueFrom}.
     *
     * <p>Implements FR5, FR8, UX2 of add-git-workflow.
     *
     * @param cloneDir the {@code --dir} project clone; never mutated (FR7)
     * @param taskId the {@code --resume} taskId, as supplied by the operator
     * @param definition the loaded pipeline the run advances through; never null
     * @param interactiveMode which role(s), if any, use the interactive console adapter
     * @param discardWork {@code --discard-work} (FR10, design D10): true resets an interrupted
     *     round's uncommitted leftovers to the last recorded round instead of salvaging them;
     *     meaningful only for the {@code null}-outcome continuation, harmless otherwise
     * @throws UsageException if no branch for {@code taskId} is found
     */
    void run(
            Path cloneDir,
            String taskId,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork) {
        ResumeBootstrap bootstrap = bootstrap(cloneDir, taskId);
        continueFrom(cloneDir, bootstrap, definition, interactiveMode, discardWork);
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
     * @return the bootstrap bundle: located branch, materialized worktree, loaded task.json
     * @throws UsageException if no branch for {@code taskId} exists locally, as a remote-tracking
     *     ref, or on {@code origin} (even after the narrow fetch attempt)
     * @throws com.github.oinsio.gnomish.app.port.git.UnsupportedStateFileVersionException if
     *     {@code task.json}'s {@code "version"} is missing or unsupported
     * @throws com.github.oinsio.gnomish.app.port.git.DivergedBranchException if the worktree's
     *     local branch tip and its {@code origin} remote-tracking tip share no ancestry
     *     relationship (FR9)
     */
    ResumeBootstrap bootstrap(Path cloneDir, String taskId) {
        return resumeBootstrap.bootstrap(cloneDir, taskId);
    }

    /**
     * Switches on {@code bootstrap.outcome()} (FR8, design D9) and drives the matching
     * continuation, delegated to {@link GitResumeContinuation}: {@code null} continues the engine
     * loop directly from {@code state.json}'s recorded position; {@code escalated}/{@code paused}
     * run their dialogs first; {@code completed} reports and returns without another engine run.
     */
    private void continueFrom(
            Path cloneDir,
            ResumeBootstrap bootstrap,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork) {
        var taskRepository = git.store().taskRepository(cloneDir, worktreesRoot);
        TaskState finalState = git.store().readRecordedState(bootstrap.worktreePath());

        RecordedOutcome outcome = bootstrap.outcome();
        var continuation = new GitResumeContinuation(assembly, git, taskRepository, cloneDir, bootstrap);
        if (outcome == null) {
            continuation.resumeFromRecordedPosition(definition, finalState, interactiveMode, discardWork);
            return;
        }
        switch (outcome) {
            case RecordedOutcome.Completed ignored -> continuation.reportCompleted(finalState);
            case RecordedOutcome.Escalated ignored ->
                continuation.resumeEscalated(definition, finalState, interactiveMode);
            case RecordedOutcome.Paused paused ->
                continuation.resumePaused(definition, finalState, paused.passedStage(), interactiveMode);
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
