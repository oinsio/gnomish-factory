package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.engine.Position;
import com.github.oinsio.gnomish.domain.engine.TaskContext;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.sandbox.Segment;
import java.nio.file.Path;
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
 * <p>Kept in sync with {@link TakeResumeRunner}: both resolve the resumed law binding through
 * {@link ResumeLawBinding} (pinned-ref tip resolution) before building their execution tail — the
 * current tip of the pinned base ref, narrow-fetched or read locally, parking or releasing the
 * claim exactly alike on the two failure branches.
 *
 * <p>Implements FR1, NFR-R4 of add-serve-sandbox-lifecycle; FR9, FR12, D3 of add-tracker-port; FR12,
 * D13 of add-base-ref-resolution.
 */
final class TakeContainerResumeRunner {

    private final SlotWiring wiring;
    private final TaskGit git;
    private final TakeContainerResumeBootstrap resumeBootstrap;

    /**
     * @param wiring the slot's equipment (D2 of introduce-slot-wiring): the task git and container
     *     support seam the bootstrap reads the branch through, the MDC key it binds, and the
     *     assembly, abort fuse, credential names and tenure every engine execution is built with
     */
    TakeContainerResumeRunner(SlotWiring wiring) {
        this.wiring = wiring;
        this.git = wiring.git();
        this.resumeBootstrap =
                new TakeContainerResumeBootstrap(git, wiring.containerTakeSupport(), wiring.taskIdMdcKey());
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
    TakeResult resumeWithoutDecision(TakeOrder order, ContainerResumeBootstrap bootstrap, TaskState finalState) {
        var support = bootstrap.support();
        var pending = support.pendingVerification().orElse(null);
        if (order.run().discardWork()) {
            support.disposeExistingEnvironment();
        } else if (finalState.position() instanceof Position.AtStage(String stage)) {
            support.reattachFor(stage);
            if (pending == null) {
                support.salvageLeftovers(bootstrap.taskId());
            }
        }
        return ResumeLawBinding.resolve(
                git.baseRefs(),
                order.run().cloneDir(),
                ResumeLawBinding.pinnedRef(bootstrap.pin(), bootstrap.baseCommit()),
                finalState,
                order.ref(),
                order.tracker(),
                lawBinding -> newExecution(lawBinding).run(order, support, bootstrap.context(), finalState, pending));
    }

    /**
     * Resumes an {@code ESCALATION} park: resets {@code attemptsUsed} to 0 with an empty attempt
     * history, committing an already-collected human reply factory-side over bare git objects
     * before any environment materializes (FR25, D19 of add-sandbox-core — mirroring {@link
     * ContainerResumeOutcomes#resumeEscalated}'s decision commit) before running the engine once.
     */
    TakeResult resumeDecided(
            TakeOrder order, ContainerResumeBootstrap bootstrap, TaskContext context, TaskState resetState) {
        return ResumeLawBinding.resolve(
                git.baseRefs(),
                order.run().cloneDir(),
                ResumeLawBinding.pinnedRef(bootstrap.pin(), bootstrap.baseCommit()),
                resetState,
                order.ref(),
                order.tracker(),
                lawBinding -> newExecution(lawBinding).run(order, bootstrap.support(), context, resetState, null));
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
        var decision = ResumeDecisionCommit.decisionFor(finalState, text);
        bootstrap.support().disposeExistingEnvironment();
        bootstrap.support().taskRepository().appendDecision(bootstrap.taskId(), decision, resetState);
        return ResumeDecisionCommit.appendTo(bootstrap.context(), decision);
    }

    private TakeContainerEngineExecution newExecution(LawBinding lawBinding) {
        return new TakeContainerEngineExecution(
                wiring.assembly(),
                wiring.abort(),
                wiring.credentialEnvVarsToScrub(),
                wiring.tenure().lossFlag(),
                lawBinding);
    }
}
