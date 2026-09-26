package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.branch.BranchRepairAction;
import com.github.oinsio.gnomish.app.branch.BranchRepairLog;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;

/**
 * The routing half of {@link TakeClaimAndWork}: with the claim already held and the heartbeat
 * already beating, decide WHERE the work runs — fresh claim or resume by whether the task branch
 * exists, then host or container by the sandbox mode the operator's bindings resolve to. {@link
 * TakeClaimAndWork} keeps the claim/crash-abort/heartbeat lifecycle; this class owns the route and
 * the equipment every route needs — the slot's {@link SlotWiring}, the two resume runners and the
 * two fresh-claim recipes built from that wiring — constructed once per slot and holding no
 * reference back to its owner (D2 of introduce-slot-wiring).
 *
 * <p>Implements FR9, FR10, D3 of add-tracker-port; FR1, FR14 of add-serve-sandbox-lifecycle;
 * FR6, NFR-O1 of harden-task-branch-contract; FR5 of introduce-slot-wiring.
 */
final class TakeWorkRouter {

    // NFR-O1: the repair line is emitted from the one place that both names the shape and decides
    // what happens to it. Stateless — it holds a logger and nothing else — so it is built once here
    // rather than threaded through the take wiring as a collaborator.
    private static final BranchRepairLog REPAIR_LOG = new BranchRepairLog();

    private final SlotWiring wiring;
    private final TakeResumeRunner resumeRunner;
    private final TakeContainerResumeRunner containerResumeRunner;
    private final TakeFreshClaim hostFreshClaim;
    private final TakeContainerFreshClaim containerFreshClaim;

    TakeWorkRouter(SlotWiring wiring, TakeResumeRunner resumeRunner, TakeContainerResumeRunner containerResumeRunner) {
        this.wiring = wiring;
        this.resumeRunner = resumeRunner;
        this.containerResumeRunner = containerResumeRunner;
        this.hostFreshClaim = new TakeFreshClaim(wiring);
        this.containerFreshClaim = new TakeContainerFreshClaim(wiring);
    }

    TakeResult locateAndWork(TakeOrder order) {
        String taskId = order.taskId();
        // One classification decides the route (FR2 of harden-task-branch-contract): the branch is
        // read once, named once, and every path below — fresh, resume, reconcile — is a case of
        // that one name rather than a predicate of its own. A lookup that could not reach origin
        // throws from here (FR6), aborting the take through the crash-abort protocol, which
        // releases the claim rather than forking a second branch for a task that already has one.
        BranchShape shape = wiring.git().branches().classifyShape(order.run().cloneDir(), taskId);
        if (shape instanceof BranchShape.Bare) {
            return freshClaim(order);
        }
        // NFR-O1: every pickup of an existing branch that is not the clean shape a healthy
        // progression expects leaves one line before its recovery owner runs — the repeat judged
        // against the task's own persisted recovery accounting (FR14), which the claim already
        // fetched, so the line costs no extra tracker read. A clean shape logs nothing, and a Bare
        // branch never reaches here: a first claim is not a repair.
        REPAIR_LOG.classified(
                taskId,
                shape,
                wiring.git().epochs().epochFor(taskId).orElse(null),
                BranchRepairAction.phrase(shape),
                order.trackerTask().abortFacts().recoveryCount());
        return resume(order, shape);
    }

    /**
     * FR1, FR14 of add-serve-sandbox-lifecycle/add-sandbox-core: the same fail-closed,
     * container-by-default selector {@code ManualRunDrive#driveGit} uses for {@code run} — a fresh
     * claim is refused, not silently routed to host, when the operator's bindings resolve to
     * container without its prerequisites (image + reachable Docker).
     */
    private TakeResult freshClaim(TakeOrder order) {
        var plan = plan(order.run().definition());
        return switch (plan.mode()) {
            case HOST -> hostFreshClaim.claim(order);
            case CONTAINER -> containerFreshClaim.claim(order, plan.segments());
        };
    }

    /**
     * FR1 of add-serve-sandbox-lifecycle, design D8: the mode choice ends here. Both arms hand the
     * SAME routing table ({@link TakeDispositionResume}) the mechanics for their mode, so a resumed
     * branch is dispatched identically either way and no routing branch can exist in one mode only.
     */
    private TakeResult resume(TakeOrder order, BranchShape shape) {
        PipelineDefinition definition = order.run().definition();
        var plan = plan(definition);
        ResumeMechanics<? extends ResumedBranch> mechanics =
                switch (plan.mode()) {
                    case HOST ->
                        new HostResumeMechanics(resumeRunner, wiring.git(), wiring.worktreesRoot(), definition);
                    case CONTAINER -> new ContainerResumeMechanics(containerResumeRunner, plan.segments(), definition);
                };
        return routingTable(mechanics, wiring.git()).resumeExisting(order, shape);
    }

    private static <B extends ResumedBranch> TakeDispositionResume<B> routingTable(
            ResumeMechanics<B> mechanics, TaskGit git) {
        return new TakeDispositionResume<>(mechanics, new TakeDecisionResume<>(mechanics), git);
    }

    private SandboxModeSelector.Plan plan(PipelineDefinition definition) {
        ContainerTakeSupport containerTakeSupport = wiring.containerTakeSupport();
        return SandboxModeSelector.plan(
                definition,
                containerTakeSupport.bindingProperties(),
                containerTakeSupport.sandboxProperties(),
                containerTakeSupport.bindingRegistry(),
                containerTakeSupport.dockerProbe());
    }
}
