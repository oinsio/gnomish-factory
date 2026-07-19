package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.BranchLocation;
import com.github.oinsio.gnomish.adapter.git.GitProcessRunner;
import com.github.oinsio.gnomish.adapter.git.GitTaskRepository;
import com.github.oinsio.gnomish.adapter.git.TaskBranchLocator;
import com.github.oinsio.gnomish.adapter.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.adapter.git.TaskWorktreeManager;
import com.github.oinsio.gnomish.adapter.git.WorktreeDivergenceCheck;
import com.github.oinsio.gnomish.adapter.git.state.TaskJsonContent;
import com.github.oinsio.gnomish.adapter.git.state.TaskOutcomeDto;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import org.slf4j.MDC;

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
 * EscalationResumeDialog#handle}, the same {@link ManualRunAssembly}-built console) rather than a
 * parallel implementation, so prompts and EOF handling match a live run's.
 *
 * <p>{@link #bootstrap} also reconciles local/origin divergence (FR9, NFR-R3, design D9) once,
 * right after the worktree is materialized and before {@code task.json} is read back — equal or
 * ahead leave the worktree untouched; behind fast-forwards it and discards uncommitted leftovers
 * (a {@link com.github.oinsio.gnomish.adapter.git.WorktreeDivergenceCheck}); diverged throws
 * {@link com.github.oinsio.gnomish.adapter.git.DivergedBranchException}. Running this once in
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

    private final ManualRunAssembly assembly;
    private final Path worktreesRoot;
    private final String taskIdMdcKey;

    /**
     * @param assembly the shared engine/ports assembly, reused from the fresh-run path — builds
     *     the same {@link com.github.oinsio.gnomish.adapter.console.DialogConsole} and {@link
     *     RunnerOutcomeLoop} a live run uses, so resume dialogs are byte-for-byte the same (UX2)
     * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
     *     worktrees are created (design D6); production wiring resolves {@code
     *     ~/.gnomish/worktrees}, tests pass a temp directory
     * @param taskIdMdcKey the MDC key to set to the branch's recorded taskId once bootstrap
     *     succeeds (design D9, task 8.2), matching {@link ManualRunRunner}'s own key
     */
    GitResumeRunner(ManualRunAssembly assembly, Path worktreesRoot, String taskIdMdcKey) {
        this.assembly = assembly;
        this.worktreesRoot = worktreesRoot;
        this.taskIdMdcKey = taskIdMdcKey;
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
        MDC.put(taskIdMdcKey, bootstrap.taskId());
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
     * @throws com.github.oinsio.gnomish.adapter.git.state.UnsupportedStateFileVersionException if
     *     {@code task.json}'s {@code "version"} is missing or unsupported
     * @throws com.github.oinsio.gnomish.adapter.git.DivergedBranchException if the worktree's
     *     local branch tip and its {@code origin} remote-tracking tip share no ancestry
     *     relationship (FR9)
     */
    ResumeBootstrap bootstrap(Path cloneDir, String taskId) {
        GitProcessRunner runner = new GitProcessRunner();
        BranchLocation location = new TaskBranchLocator(runner).locate(cloneDir, taskId);
        if (location instanceof BranchLocation.NotFound) {
            throw new UsageException("could not resume task \"" + taskId + "\": no branch \""
                    + TaskIdSanitizer.branchName(taskId)
                    + "\" found locally, as a remote-tracking ref, or on origin (even after a fetch attempt)");
        }

        String branchName = TaskIdSanitizer.branchName(taskId);
        Path worktree = new TaskWorktreeManager(runner, worktreesRoot).ensureWorktree(cloneDir, taskId, branchName);

        // FR9, NFR-R3: reconcile local/origin divergence before task.json is read back — a
        // BEHIND outcome fast-forwards the worktree and discards uncommitted leftovers, which
        // can change what task.json/state.json actually contain on disk. Runs once, here, for
        // every resume outcome (null/escalated/paused/completed alike) since divergence is a
        // general resume precondition (design D9), not specific to one outcome branch.
        new WorktreeDivergenceCheck(runner, worktree).reconcile(taskId, branchName);

        TaskJsonContent content = GitFreshTaskSupport.readTaskJson(worktree);
        return new ResumeBootstrap(
                taskId,
                content.context(),
                content.outcome(),
                content.lastEscalation(),
                worktree,
                branchName,
                content.baseCommit());
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
        GitProcessRunner runner = new GitProcessRunner();
        GitTaskRepository taskRepository = new GitTaskRepository(runner, cloneDir, worktreesRoot);
        TaskState finalState = GitFreshTaskSupport.readFinalState(bootstrap.worktreePath());

        TaskOutcomeDto outcome = bootstrap.outcome();
        var continuation = new GitResumeContinuation(assembly, runner, taskRepository, cloneDir, bootstrap);
        if (outcome == null) {
            continuation.resumeFromRecordedPosition(definition, finalState, interactiveMode, discardWork);
            return;
        }
        switch (outcome) {
            case TaskOutcomeDto.Completed ignored -> continuation.reportCompleted(finalState);
            case TaskOutcomeDto.Escalated ignored ->
                continuation.resumeEscalated(definition, finalState, interactiveMode);
            case TaskOutcomeDto.Paused paused ->
                continuation.resumePaused(definition, finalState, paused.passedStage(), interactiveMode);
            case TaskOutcomeDto.Aborted ignored ->
                // An Aborted task.json means a prior visit's durability guarantee broke; nothing
                // to resume automatically. A plain usage error keeps the operator from building
                // on possibly-inconsistent state instead of silently continuing.
                throw new UsageException("cannot resume task \"" + bootstrap.taskId()
                        + "\": its last recorded outcome is Aborted — inspect the kept worktree at "
                        + bootstrap.worktreePath() + " and start a new task instead");
        }
    }
}
