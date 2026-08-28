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
import com.github.oinsio.gnomish.sandbox.Segment;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The container-mode counterpart of {@link TakeResumeRunner} (FR1, NFR-R4 of add-serve-sandbox-
 * lifecycle): resumes a sandboxed {@code take} from the branch alone, reattaching the environment
 * (start a stopped box, recreate over a surviving volume, or seed a fresh clone — {@link
 * com.github.oinsio.gnomish.app.port.run.SandboxRunSupport#reattachFor}) in place of {@link
 * TakeResumeRunner}'s worktree materialize, then driving {@link TakeContainerEngineExecution}
 * instead of {@link RunnerOutcomeLoop} ({@code take} never opens a console dialog, design D12).
 *
 * <p>Two resume entry points mirror {@link TakeResumeRunner}'s own two shapes (design D3): {@link
 * #resumeWithoutDecision} for a {@code null}/{@code CHECKPOINT}/{@code INFRA} return, no decision
 * involved; {@link #resumeDecided} for an {@code ESCALATION} return, from a context {@link
 * #appendDecision} has already committed when there was a reply to commit.
 *
 * <p>Implements FR1, NFR-R4 of add-serve-sandbox-lifecycle; FR9, FR12, D3 of add-tracker-port.
 */
final class TakeContainerResumeRunner {

    private final RunAssembly assembly;
    private final AbortHandler abortHandler;
    private final int abortThreshold;
    private final List<String> credentialEnvVarsToScrub;
    private final ClaimLossFlag claimLossFlag;
    private final TakeContainerResumeBootstrap resumeBootstrap;

    TakeContainerResumeRunner(
            RunAssembly assembly,
            TaskGit git,
            ContainerTakeSupport containerTakeSupport,
            AbortHandler abortHandler,
            int abortThreshold,
            List<String> credentialEnvVarsToScrub,
            ClaimLossFlag claimLossFlag,
            String taskIdMdcKey) {
        this.assembly = assembly;
        this.abortHandler = abortHandler;
        this.abortThreshold = abortThreshold;
        this.credentialEnvVarsToScrub = credentialEnvVarsToScrub;
        this.claimLossFlag = claimLossFlag;
        this.resumeBootstrap = new TakeContainerResumeBootstrap(git, containerTakeSupport, taskIdMdcKey);
    }

    /**
     * Locates the task branch and loads its bundle over bare git objects.
     *
     * @return the loaded bundle, or {@code null} for a delivered-and-cleaned branch tip (see {@link
     *     TakeContainerResumeBootstrap#bootstrap})
     */
    @Nullable
    ContainerResumeBootstrap bootstrap(
            Path cloneDir, String taskId, List<Segment> segments, PipelineDefinition definition) {
        return resumeBootstrap.bootstrap(cloneDir, taskId, segments, definition);
    }

    /**
     * Resumes a {@code null} (process died mid-visit), {@code CHECKPOINT}, or {@code INFRA} park:
     * a snapshot commit found unrecorded at the branch tip is an interrupted verification (FR21 of
     * add-sandbox-core) — re-verified against exactly that attempt commit, no salvage, no agent
     * re-run; otherwise the environment is reattached and uncommitted leftovers salvaged in-box
     * (or, on {@code --discard-work}, disposed so the next reattach seeds a fresh clone at the
     * recorded tip) — {@link ContainerResumeOutcomes#resumeFromRecordedPosition}'s exact sequence,
     * reused here for the salvage/discard decision, then routed through {@link
     * TakeContainerEngineExecution} instead of {@link ContainerTerminalDrive} (NFR-R4).
     */
    TakeResult resumeWithoutDecision(
            Path cloneDir,
            ContainerResumeBootstrap bootstrap,
            PipelineDefinition definition,
            TaskState finalState,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        var support = bootstrap.support();
        var pending = support.pendingVerification().orElse(null);
        if (discardWork) {
            support.disposeExistingEnvironment();
        } else if (finalState.position() instanceof Position.AtStage(String stage)) {
            support.reattachFor(stage);
            if (pending == null) {
                support.salvageLeftovers(bootstrap.taskId());
            }
        }
        return newExecution(cloneDir)
                .run(
                        support,
                        definition,
                        bootstrap.context(),
                        finalState,
                        interactiveMode,
                        tracker,
                        ref,
                        instanceId,
                        bootstrap.taskId(),
                        pending);
    }

    /**
     * Resumes an {@code ESCALATION} park: resets {@code attemptsUsed} to 0 with an empty attempt
     * history, committing an already-collected human reply factory-side over bare git objects
     * before any environment materializes (FR25, D19 of add-sandbox-core — mirroring {@link
     * ContainerResumeOutcomes#resumeEscalated}'s decision commit) before running the engine once.
     */
    TakeResult resumeDecided(
            Path cloneDir,
            ContainerResumeBootstrap bootstrap,
            PipelineDefinition definition,
            TaskContext context,
            TaskState resetState,
            RunArguments.InteractiveMode interactiveMode,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        return newExecution(cloneDir)
                .run(
                        bootstrap.support(),
                        definition,
                        context,
                        resetState,
                        interactiveMode,
                        tracker,
                        ref,
                        instanceId,
                        bootstrap.taskId(),
                        null);
    }

    /**
     * Commits the human's decision to the branch — the durable intent the tracker acknowledge
     * follows (FR12 of harden-task-branch-contract) — after disposing the kept box that carried the
     * park (FR17, design D12 of harden-task-branch-contract): the box's clone cannot learn of this commit, so a later harvest from
     * it would diverge, and the next round's box is materialized from a tip that already contains
     * the decision.
     */
    TaskContext appendDecision(
            ContainerResumeBootstrap bootstrap, TaskState finalState, TaskState resetState, String text) {
        String stage = finalState.position() instanceof Position.AtStage(String name) ? name : null;
        var decision = new Decision(text, stage, "tracker", Clock.systemUTC().instant());
        bootstrap.support().disposeExistingEnvironment();
        bootstrap.support().taskRepository().appendDecision(bootstrap.taskId(), decision, resetState);
        var decisions = new ArrayList<>(bootstrap.context().decisions());
        decisions.add(decision);
        var context = bootstrap.context();
        return new TaskContext(context.taskId(), context.title(), context.body(), decisions);
    }

    private TakeContainerEngineExecution newExecution(Path cloneDir) {
        return new TakeContainerEngineExecution(
                assembly, abortHandler, abortThreshold, credentialEnvVarsToScrub, claimLossFlag, cloneDir);
    }
}
