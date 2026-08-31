package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.lease.ClaimLossFlag;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.AbortHandler;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.Decision;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

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
 * <p>Implements FR9, FR12, D3 of add-tracker-port.
 */
final class TakeResumeRunner {

    private final RunAssembly assembly;
    private final TaskGit git;
    private final Path worktreesRoot;
    private final AbortHandler abortHandler;
    private final int abortThreshold;
    private final List<String> credentialEnvVarsToScrub;
    private final ClaimLossFlag claimLossFlag;
    private final TakeResumeBootstrap resumeBootstrap;

    /**
     * @param assembly the shared engine/ports assembly, reused from the manual-run path — builds
     *     the same {@link com.github.oinsio.gnomish.domain.engine.EnginePorts} bundle a live run
     *     uses, minus the dialog console take never opens
     * @param worktreesRoot the root directory under which {@code <project-name>/<taskId>/}
     *     worktrees are created (design D6); production wiring resolves {@code
     *     ~/.gnomish/worktrees}, tests pass a temp directory
     * @param git the task-git capability set the resumed run's store, branch and worktree
     *     operations come from; never null
     * @param taskIdMdcKey the MDC key set to the branch's recorded taskId once bootstrap succeeds,
     *     matching {@link GitResumeRunner}'s own key
     * @param abortHandler the infrastructure-abort protocol (task 5.3), applied when a resumed
     *     engine run returns {@code Aborted}; never null
     * @param abortThreshold the configured abort-fuse threshold (K) passed to {@code
     *     abortHandler}; positive
     * @param credentialEnvVarsToScrub the active tracker adapter's declared credential
     *     environment variable names (design D17, NFR-S1 of add-tracker-port), threaded into
     *     every {@link TakeEngineExecution} this runner constructs; never null
     * @param claimLossFlag the per-run heartbeat claim-loss flag (task 6.3, FR8 of
     *     add-claim-heartbeat), threaded into every {@link TakeEngineExecution} this runner
     *     constructs so the round boundary reacts to a beat-detected loss as a revocation; never null
     */
    TakeResumeRunner(
            RunAssembly assembly,
            TaskGit git,
            Path worktreesRoot,
            String taskIdMdcKey,
            AbortHandler abortHandler,
            int abortThreshold,
            List<String> credentialEnvVarsToScrub,
            ClaimLossFlag claimLossFlag) {
        this.assembly = assembly;
        this.git = git;
        this.worktreesRoot = worktreesRoot;
        this.abortHandler = abortHandler;
        this.abortThreshold = abortThreshold;
        this.credentialEnvVarsToScrub = credentialEnvVarsToScrub;
        this.claimLossFlag = claimLossFlag;
        this.resumeBootstrap = new TakeResumeBootstrap(git, worktreesRoot, taskIdMdcKey);
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
     * @return the bootstrap bundle: located branch, materialized worktree, loaded task.json
     * @throws UsageException if no branch for {@code taskId} is found
     */
    public ResumeBootstrap bootstrap(Path cloneDir, String taskId) {
        return resumeBootstrap.bootstrap(cloneDir, taskId);
    }

    /**
     * Resumes a {@code null} (process died mid-visit), {@code CHECKPOINT}, or {@code INFRA} park:
     * none of these carry a decision or need the attempt-counter reset, since none of them burned
     * an attempt (only quality-failure rounds do, per {@code TaskState.recordQualityFailure} vs
     * {@code recordUnburnedRound}). Salvages (default) or discards ({@code --discard-work}) the
     * interrupted round's uncommitted leftovers exactly as {@link
     * GitResumeContinuation#resumeFromRecordedPosition} does, then runs the engine once.
     *
     * <p>Implements FR9 of add-tracker-port.
     *
     * @param cloneDir the project clone; never mutated
     * @param bootstrap the located/materialized bundle from {@link #bootstrap}
     * @param definition the pipeline the run advances through
     * @param finalState the state to resume from, unchanged from the park
     * @param interactiveMode which role(s) use the interactive adapter
     * @param discardWork {@code --discard-work}: true discards interrupted leftovers instead of
     *     salvaging them
     * @param tracker the tracker port, for the revocation check wrapped around persistence
     * @param ref the task's tracker identity
     * @param instanceId this factory instance's identity
     * @return the mapped {@link TakeResult} for the engine run
     */
    public TakeResult resumeWithoutDecision(
            Path cloneDir,
            ResumeBootstrap bootstrap,
            PipelineDefinition definition,
            TaskState finalState,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        var salvage = git.worktrees().salvage(bootstrap.worktreePath());
        if (discardWork) {
            salvage.discard();
        } else {
            salvage.salvage(bootstrap.taskId());
        }

        return newExecution(cloneDir)
                .run(definition, bootstrap, bootstrap.context(), finalState, interactiveMode, tracker, ref, instanceId);
    }

    /**
     * Resumes an {@code ESCALATION} park ({@code AttemptsExhausted} or {@code DecisionNeeded}):
     * resets {@code attemptsUsed} to 0 with an empty attempt history — {@code
     * EscalationResumeDialog#handleResumable}'s formula — then runs the engine once. {@code
     * decisionText} is the already-collected human reply (task 5.7 collects it) or {@code null}:
     * an {@code AttemptsExhausted} park may resume on the return alone (design D12). Non-blank, it
     * is appended via {@link com.github.oinsio.gnomish.app.port.TaskRepository#appendDecision}
     * (author {@code "tracker"}, since the reply is a tracker comment, not a console answer);
     * {@code null}/blank appends nothing, mirroring {@code handleResumable}'s blank-answer case.
     *
     * <p>Implements FR9, FR12, D3, D12 of add-tracker-port.
     *
     * @param cloneDir the project clone; never mutated
     * @param bootstrap the located/materialized bundle from {@link #bootstrap}
     * @param definition the pipeline the run advances through
     * @param context the task context the run continues from — the human's decision included when
     *     one was committed, the branch's own otherwise (design D12)
     * @param resetState the escalated state with its attempt counter reset
     * @param interactiveMode which role(s) use the interactive adapter
     * @param tracker the tracker port, for the revocation check wrapped around persistence
     * @param ref the task's tracker identity
     * @param instanceId this factory instance's identity
     * @return the mapped {@link TakeResult} for the engine run
     */
    public TakeResult resumeDecided(
            Path cloneDir,
            ResumeBootstrap bootstrap,
            PipelineDefinition definition,
            TaskContext context,
            TaskState resetState,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        return newExecution(cloneDir)
                .run(definition, bootstrap, context, resetState, interactiveMode, tracker, ref, instanceId);
    }

    /**
     * Commits the human's decision to the branch — the durable intent the tracker acknowledge
     * follows (FR12 of harden-task-branch-contract), landing in one commit with the attempt-counter
     * reset it implies (FR4).
     */
    TaskContext appendDecision(
            Path cloneDir, ResumeBootstrap bootstrap, TaskState finalState, TaskState resetState, String text) {
        var taskRepository = git.store().taskRepository(cloneDir, worktreesRoot);
        String stage = finalState.position() instanceof Position.AtStage(String name) ? name : null;
        var decision = new Decision(text, stage, "tracker", Clock.systemUTC().instant());
        taskRepository.appendDecision(bootstrap.taskId(), decision, resetState);
        var decisions = new ArrayList<>(bootstrap.context().decisions());
        decisions.add(decision);
        var context = bootstrap.context();
        return new TaskContext(context.taskId(), context.title(), context.body(), decisions);
    }

    private TakeEngineExecution newExecution(Path cloneDir) {
        return new TakeEngineExecution(
                assembly,
                git,
                cloneDir,
                worktreesRoot,
                abortHandler,
                abortThreshold,
                credentialEnvVarsToScrub,
                claimLossFlag);
    }
}
