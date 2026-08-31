package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.branch.BranchQuarantineException;
import com.github.oinsio.gnomish.app.branch.BranchRecoveryFailedException;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.tracker.InstanceId;
import com.github.oinsio.gnomish.app.port.tracker.TaskRef;
import com.github.oinsio.gnomish.app.port.tracker.Tracker;
import com.github.oinsio.gnomish.app.take.TakeResult;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import java.nio.file.Path;

/**
 * The "branch already exists" half of {@link TakeDisposition}'s {@code Ready} case (FR9, D3): the
 * ONE routing table a just-claimed existing branch is dispatched through, in host and container
 * mode alike (design D8 of add-serve-sandbox-lifecycle).
 *
 * <p>The table's input is the branch's classified {@linkplain
 * com.github.oinsio.gnomish.domain.branch.BranchShape shape} — never the tracker (which post-claim
 * always reports {@code Working} held by us), and never a predicate this file derives for itself
 * (FR2 of harden-task-branch-contract). Because the shape set is sealed, the switch below has no
 * default: adding a shape fails the build here until this route names it, which is the property
 * that keeps "every shape has exactly one recovery owner" true as the contract grows. The routes
 * that need a loaded branch continue in {@link TakeLoadedBranchRoutes}.
 *
 * <p>Everything mode-specific — worktree versus box, where {@code state.json} is read, whether a
 * "tracker-write pending" marker can be cleared at all — is reached through {@link ResumeMechanics}.
 *
 * <p>Implements FR9, D3 of add-tracker-port; FR10, D10, NFR-C1 of add-claim-heartbeat; FR1 of
 * add-serve-sandbox-lifecycle; FR2, FR15 of harden-task-branch-contract.
 *
 * @param <B> the loaded-branch bundle {@code mechanics} produces
 */
record TakeDispositionResume<B extends ResumedBranch>(
        ResumeMechanics<B> mechanics, TakeDecisionResume<B> decisionResume, TaskGit git) {

    /**
     * Dispatches {@code taskId}'s branch on its classified shape (see class javadoc).
     *
     * <p>Implements FR9, D3 of add-tracker-port; FR1 of add-serve-sandbox-lifecycle; FR2, FR15 of
     * harden-task-branch-contract.
     *
     * @param shape the branch tip's classification, as the caller read it once
     */
    TakeResult resumeExisting(
            Path cloneDir,
            BranchShape shape,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            String taskId,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        if (shape.isClean()) {
            return routeByShape(cloneDir, shape, interactiveMode, discardWork, taskId, tracker, ref, instanceId);
        }
        // A non-clean shape is being repaired, and this is the one place that knows it: a failure
        // below is a failed recovery, so it is named as one before it reaches the take's crash
        // boundary, where the two categories of the unified accounting are otherwise
        // indistinguishable (FR14). Deliberate control flow keeps its own meaning — a usage refusal
        // still exits 2, and the two branch verdicts already carry their own classification.
        try {
            return routeByShape(cloneDir, shape, interactiveMode, discardWork, taskId, tracker, ref, instanceId);
        } catch (UsageException | BranchQuarantineException | BranchRecoveryFailedException classified) {
            throw classified;
        } catch (RuntimeException failure) {
            throw new BranchRecoveryFailedException(taskId, shape, failure);
        }
    }

    private TakeResult routeByShape(
            Path cloneDir,
            BranchShape shape,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            String taskId,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        return switch (shape) {
            // Delivery is terminal: the branch is done and only the tracker write may be owed.
            case BranchShape.Delivered() ->
                TakeReconcileFinish.deliverCompleted(git, cloneDir, taskId, tracker, ref, instanceId);
            // The replica reconciler owns a stale-epoch tip: loading the branch runs it (bootstrap
            // reconciles before it reads), after which the tip is classified again and routed on
            // what it has become. One pass only — a tip still stale after its own reconciliation is
            // not converging, and quarantining beats looping.
            case BranchShape.StaleEpoch() ->
                afterReconciliation(cloneDir, interactiveMode, discardWork, taskId, tracker, ref, instanceId);
            case BranchShape.Created(),
                    BranchShape.InProgress(),
                    BranchShape.Answered(),
                    BranchShape.Parked(),
                    BranchShape.CompletedUncleaned() ->
                routes().route(cloneDir, interactiveMode, discardWork, taskId, tracker, ref, instanceId);
            // Bare is routed to the fresh-claim path before resume is ever reached (TakeWorkRouter),
            // so arriving here with it is a routing defect, not a branch state — and the three
            // non-recoverable shapes stop the run with their own diagnosis (FR15).
            case BranchShape.Bare() -> throw new BranchQuarantineException(taskId, shape);
            case BranchShape.UnsupportedVersion ignoredVersion -> throw new BranchQuarantineException(taskId, shape);
            case BranchShape.Corrupt ignoredCorrupt -> throw new BranchQuarantineException(taskId, shape);
            case BranchShape.Unknown ignoredUnknown -> throw new BranchQuarantineException(taskId, shape);
        };
    }

    private TakeLoadedBranchRoutes<B> routes() {
        return new TakeLoadedBranchRoutes<>(mechanics, decisionResume, git);
    }

    /**
     * Re-routes a stale-epoch branch on the shape it has after its replica pair reconciled. The
     * recursion is depth-one by construction: the only shape that re-enters this method is {@link
     * BranchShape.StaleEpoch}, and a tip that is still stale after reconciliation is quarantined
     * instead of routed again.
     */
    private TakeResult afterReconciliation(
            Path cloneDir,
            RunArguments.InteractiveMode interactiveMode,
            boolean discardWork,
            String taskId,
            Tracker tracker,
            TaskRef ref,
            InstanceId instanceId) {
        mechanics.loadBranch(cloneDir, taskId);
        BranchShape reconciled = git.branches().classifyShape(cloneDir, taskId);
        if (reconciled instanceof BranchShape.StaleEpoch) {
            throw new BranchQuarantineException(taskId, reconciled);
        }
        return resumeExisting(cloneDir, reconciled, interactiveMode, discardWork, taskId, tracker, ref, instanceId);
    }
}
