package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.take.TakeResult;
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
 * #resumeWithoutDecision} for a tip whose outcome is {@code null} — never recorded, or consumed by
 * the approval or the resumed write that preceded the call — no decision involved; {@link #resumeDecided} for an {@code ESCALATION} return, from a context {@link
 * #appendDecision} has already committed when there was a reply to commit.
 *
 * <p>Kept in sync with {@link TakeResumeRunner}: both resolve the resumed law binding through
 * {@link ResumeLawBinding} (pinned-ref tip resolution) before building their execution tail — the
 * current tip of the pinned base ref, narrow-fetched or read locally, parking or releasing the
 * claim exactly alike on the two failure branches. The two outcome-clearing writes a reply-less
 * continuation needs — the approval of a gate and the resumed write — are not part of this pair:
 * they live in the shared seam, {@link ResumeMechanics#approveCheckpoint} and {@link
 * ResumeMechanics#resumeFrom}, implemented once per medium by {@link HostResumeMechanics} and
 * {@link ContainerResumeMechanics} (design D6 of make-checkpoint-gate-durable).
 *
 * <p>Implements FR1, NFR-R4 of add-serve-sandbox-lifecycle; FR9, FR12, D3 of add-tracker-port; FR12,
 * D13 of add-base-ref-resolution; FR18 of make-checkpoint-gate-durable.
 */
final class TakeContainerResumeRunner {

    private final SlotWiring wiring;
    private final TakeContainerResumeBootstrap resumeBootstrap;

    /**
     * @param wiring the slot's equipment (D2 of introduce-slot-wiring): the task git and container
     *     support seam the bootstrap reads the branch through, the MDC key it binds, and the
     *     assembly, abort fuse, credential names and tenure every engine execution is built with
     */
    TakeContainerResumeRunner(SlotWiring wiring) {
        this.wiring = wiring;
        this.resumeBootstrap =
                new TakeContainerResumeBootstrap(wiring.git(), wiring.containerTakeSupport(), wiring.taskIdMdcKey());
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
     * Resumes a tip whose outcome is {@code null} — a process that died mid-visit, or a {@code
     * CHECKPOINT}/{@code INFRA} return whose approval or resumed write already landed (FR4, FR7 of
     * make-checkpoint-gate-durable). The snapshot check below therefore runs on every path: no
     * recorded outcome can route around it, and a tip that carried an outcome was a lifecycle
     * commit, never a snapshot, so the write that consumed it hid none (design D4, 6a). A snapshot
     * commit found unrecorded at the branch tip is an interrupted verification (FR21 of
     * add-sandbox-core) — re-verified against exactly that attempt commit, no salvage, no agent
     * re-run; otherwise the environment is reattached and uncommitted leftovers salvaged in-box
     * (or, on {@code --discard-work}, disposed so the next reattach seeds a fresh clone at the
     * recorded tip). That preparation has one owner, {@link ContainerResumePreparation#prepare},
     * shared with {@code run --resume} (FR18 of make-checkpoint-gate-durable); this runner adds
     * only its own drive, through {@link TakeContainerEngineExecution} instead of {@link
     * ContainerTerminalDrive} (NFR-R4).
     */
    TakeResult resumeWithoutDecision(TakeOrder order, ContainerResumeBootstrap bootstrap, TaskState finalState) {
        var support = bootstrap.support();
        var pending = ContainerResumePreparation.prepare(
                support, order.run().discardWork(), finalState.position(), bootstrap.taskId());
        return ResumeLawBinding.resolve(
                wiring.git().baseRefs(),
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
                wiring.git().baseRefs(),
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
