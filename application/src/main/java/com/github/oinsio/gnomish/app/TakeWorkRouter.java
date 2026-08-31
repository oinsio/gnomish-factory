package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.branch.BranchRepairAction;
import com.github.oinsio.gnomish.app.branch.BranchRepairLog;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The routing half of {@link TakeClaimAndWork}: with the claim already held and the heartbeat
 * already beating, decide WHERE the work runs — fresh claim or resume by whether the task branch
 * exists, then host or container by the sandbox mode the operator's bindings resolve to. Extracted
 * from {@link TakeClaimAndWork}, which keeps the claim/crash-abort/heartbeat lifecycle, so neither
 * file carries both concerns (file-size target, {@code process-invariants.md}); the behavior is
 * unchanged and {@link TakeClaimAndWork} is passed whole as the parameter object, the same shape
 * {@code ContainerRunTermination} uses for {@code ContainerRunSupport}.
 *
 * <p>Implements FR9, FR10, D3 of add-tracker-port; FR1, FR14 of add-serve-sandbox-lifecycle;
 * FR6, NFR-O1 of harden-task-branch-contract.
 */
final class TakeWorkRouter {

    // NFR-O1: the repair line is emitted from the one place that both names the shape and decides
    // what happens to it. Stateless — it holds a logger and nothing else — so it is built once here
    // rather than threaded through the take wiring as a collaborator.
    private static final BranchRepairLog REPAIR_LOG = new BranchRepairLog();

    private TakeWorkRouter() {}

    static TakeResult locateAndWork(
            TakeClaimAndWork w,
            Path cloneDir,
            @Nullable String base,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            TrackerTask trackerTask,
            Tracker tracker,
            InstanceId instanceId) {
        TaskRef ref = trackerTask.ref();
        String taskId = trackerTask.snapshot().id();
        // One classification decides the route (FR2 of harden-task-branch-contract): the branch is
        // read once, named once, and every path below — fresh, resume, reconcile — is a case of
        // that one name rather than a predicate of its own. A lookup that could not reach origin
        // throws from here (FR6), aborting the take through the crash-abort protocol, which
        // releases the claim rather than forking a second branch for a task that already has one.
        BranchShape shape = w.git.branches().classifyShape(cloneDir, taskId);
        if (shape instanceof BranchShape.Bare) {
            return freshClaim(w, cloneDir, base, definition, interactiveMode, trackerTask, tracker, instanceId);
        }
        // NFR-O1: every pickup of an existing branch that is not the clean shape a healthy
        // progression expects leaves one line before its recovery owner runs — the repeat judged
        // against the task's own persisted recovery accounting (FR14), which the claim already
        // fetched, so the line costs no extra tracker read. A clean shape logs nothing, and a Bare
        // branch never reaches here: a first claim is not a repair.
        REPAIR_LOG.classified(
                taskId,
                shape,
                w.epochs.epochFor(taskId).orElse(null),
                BranchRepairAction.phrase(shape),
                trackerTask.abortFacts().recoveryCount());
        return resume(w, cloneDir, shape, definition, interactiveMode, discardWork, taskId, tracker, ref, instanceId);
    }

    /**
     * FR1, FR14 of add-serve-sandbox-lifecycle/add-sandbox-core: the same fail-closed,
     * container-by-default selector {@code ManualRunDrive#driveGit} uses for {@code run} — a fresh
     * claim is refused, not silently routed to host, when the operator's bindings resolve to
     * container without its prerequisites (image + reachable Docker).
     */
    private static TakeResult freshClaim(
            TakeClaimAndWork w,
            Path cloneDir,
            @Nullable String base,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            TrackerTask trackerTask,
            Tracker tracker,
            InstanceId instanceId) {
        var plan = plan(w, definition);
        return switch (plan.mode()) {
            case HOST ->
                TakeFreshClaim.claim(
                        w.assembly,
                        w.git,
                        w.worktreesRoot,
                        w.abortHandler,
                        w.abortThreshold,
                        w.credentialEnvVarsToScrub,
                        cloneDir,
                        base,
                        definition,
                        interactiveMode,
                        trackerTask,
                        tracker,
                        instanceId,
                        w.claimLossFlag);
            case CONTAINER ->
                TakeContainerFreshClaim.claim(
                        w.assembly,
                        w.git,
                        w.containerTakeSupport,
                        plan.segments(),
                        w.abortHandler,
                        w.abortThreshold,
                        w.credentialEnvVarsToScrub,
                        cloneDir,
                        base,
                        definition,
                        interactiveMode,
                        trackerTask,
                        tracker,
                        instanceId,
                        w.claimLossFlag);
        };
    }

    /**
     * FR1 of add-serve-sandbox-lifecycle, design D8: the mode choice ends here. Both arms hand the
     * SAME routing table ({@link TakeDispositionResume}) the mechanics for their mode, so a resumed
     * branch is dispatched identically either way and no routing branch can exist in one mode only.
     */
    private static TakeResult resume(
            TakeClaimAndWork w,
            Path cloneDir,
            BranchShape shape,
            PipelineDefinition definition,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            String taskId,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        var plan = plan(w, definition);
        ResumeMechanics<? extends ResumedBranch> mechanics =
                switch (plan.mode()) {
                    case HOST -> new HostResumeMechanics(w.resumeRunner, w.git, w.worktreesRoot, definition);
                    case CONTAINER ->
                        new ContainerResumeMechanics(w.containerResumeRunner, plan.segments(), definition);
                };
        return routingTable(mechanics, w.git)
                .resumeExisting(cloneDir, shape, interactiveMode, discardWork, taskId, tracker, ref, instanceId);
    }

    private static <B extends ResumedBranch> TakeDispositionResume<B> routingTable(
            ResumeMechanics<B> mechanics, TaskGit git) {
        return new TakeDispositionResume<>(mechanics, new TakeDecisionResume<>(mechanics), git);
    }

    private static SandboxModeSelector.Plan plan(TakeClaimAndWork w, PipelineDefinition definition) {
        return SandboxModeSelector.plan(
                definition,
                w.containerTakeSupport.bindingProperties(),
                w.containerTakeSupport.sandboxProperties(),
                w.containerTakeSupport.bindingRegistry(),
                w.containerTakeSupport.dockerProbe());
    }
}
