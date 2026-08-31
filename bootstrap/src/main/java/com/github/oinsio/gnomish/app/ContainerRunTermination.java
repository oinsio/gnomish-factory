package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.adapter.git.OriginReconciliation;
import com.github.oinsio.gnomish.app.lease.LivenessVerdict;
import com.github.oinsio.gnomish.domain.engine.TaskOutcome;
import com.github.oinsio.gnomish.domain.engine.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The terminal-boundary operations of {@link ContainerRunSupport} (FR6, FR11, FR21, FR22, NFR-R2
 * of add-sandbox-core): the sweep, completion, park, abort and keep paths — every write that closes
 * a container run. Reading the branch back is the opposite direction and belongs to {@link
 * ContainerTipReader}.
 */
final class ContainerRunTermination {

    private static final Logger log = LoggerFactory.getLogger(ContainerRunTermination.class);

    private ContainerRunTermination() {}

    /**
     * Runs the startup sweep-lifecycle pass (FR6, FR7 of add-serve-sandbox-lifecycle): {@code run}
     * holds no project-wide claim listing, so tracked objects of OTHER tasks degrade to
     * skipped-no-verdict (never touched) while this run's own {@code mode=manual} objects are
     * governed by age alone. A missing Docker runtime — or any other failure of the pass — is
     * logged and swallowed here, never a failed run: the sweep is hygiene, not the task.
     */
    static void sweepOrphans(ContainerRunSupport support) {
        try {
            String summary = support.sandboxLifecyclePass.run(support.cloneDir, new LivenessVerdict.NoVerdict());
            if (!summary.isBlank()) {
                log.info("gnomish run: {}", summary);
            }
        } catch (RuntimeException e) {
            log.info("gnomish run: sandbox lifecycle sweep skipped: {}", e.toString());
        }
    }

    /**
     * Completed terminal boundary (D19 ordering): dispose the environment first — the last in-box
     * commit was the state commit — then record the {@code Completed} outcome commit factory-side.
     * That commit is the completion's durable intent and nothing more: the cleanup commit is the
     * destructive last step and runs through {@code finishCleanup} once the terminal tracker write
     * has landed (FR10 of harden-task-branch-contract). The push that follows each commit is the
     * repository decorator's (FR1, FR6 of fix-lifecycle-push), not this boundary's.
     */
    static void completeAndDispose(ContainerRunSupport support, TaskState finalState) {
        support.judgeEnvironments.disposeCurrent();
        support.lease.dispose();
        support.taskRepository.recordOutcome(support.taskId, new TaskOutcome.Completed(finalState));
        reconcileRemote(support);
    }

    /**
     * The park's durable intent (FR10, design D12 of harden-task-branch-contract): the outcome
     * commit carrying the pending marker, built factory-side over bare objects and pushed by the
     * repository decorator. The box is stopped by the time this runs, so no in-box channel is
     * involved — and by that change's FR17 this is the last factory-side commit until the box is
     * disposed.
     */
    static void recordPark(ContainerRunSupport support, TaskOutcome outcome) {
        support.taskRepository.recordOutcome(support.taskId, outcome);
    }

    /**
     * The terminal write's receipt (FR10 of harden-task-branch-contract): the cleared pending
     * marker, so a later resume reads the park as settled rather than orphaned.
     */
    static void confirmTerminalWrite(ContainerRunSupport support) {
        support.taskRepository.confirmTerminalWrite(support.taskId);
    }

    /**
     * The completion's destructive last step (FR10 of harden-task-branch-contract): the cleanup
     * commit stripping {@code .gnomish-task/} from the tip, run only once the tracker finish has
     * landed. No live box is required — the state commit was the last in-box commit (D15, D19).
     */
    static void finishCleanup(ContainerRunSupport support) {
        support.taskRepository.finishCleanup(support.taskId);
    }

    /**
     * Aborted terminal boundary (D19): the outcome commits on the last harvested tip, and the
     * repository decorator pushes it best-effort (FR1, FR6 of fix-lifecycle-push); the violating
     * box is kept as evidence — the caller's keep path stops it (see {@code
     * ContainerRunSupport.keepStopped}), volume and network retained. That keep path is also where
     * this boundary's remote reconciliation runs: every caller of this method follows it with
     * {@code keepStopped}, so the touchpoint sits there once rather than in each recording arm.
     */
    static void recordAborted(ContainerRunSupport support, TaskOutcome.Aborted outcome) {
        support.taskRepository.recordOutcome(support.taskId, outcome);
    }

    /**
     * The terminal-boundary touchpoint of a container run (FR3 of fix-lifecycle-push): the level
     * safety net behind the outcome's own recording push, delivering a tip an earlier round's
     * failed push left behind. The local tip comes from this mode's native reader — the bare-object
     * {@code resolveRef} — per design D3, so no third tip-reading implementation appears; a branch
     * that vanished from under the run is nothing to reconcile.
     */
    private static void reconcileRemote(ContainerRunSupport support) {
        support.gitObjects
                .resolveRef("refs/heads/" + support.branch)
                .ifPresent(tip -> new OriginReconciliation(support.runner)
                        .reconcile(support.taskId, "terminal-boundary", support.cloneDir, support.branch, tip.hex()));
    }

    /**
     * Keep semantics for a run that ended without disposing (a park, an abort, an EOF-interrupted
     * dialog): the container is stopped so no gnome process keeps executing, volume and network
     * remain for salvage and resume; fresh judge boxes are disposed — they hold nothing durable.
     *
     * <p>This is the keep half of the terminal-boundary touchpoint (FR3 of fix-lifecycle-push);
     * {@link #completeAndDispose} covers the dispose half. It matters most for a park ({@code
     * Escalated}/{@code Paused}), which in container mode records no lifecycle commit of its own
     * (design D4, NG1) and so has no recording push behind it at all: the branch tip the human is
     * about to be pointed at is the last round's state commit, and this is the run's only remaining
     * chance to deliver it when that round's own push was lost.
     */
    static void keepStopped(ContainerRunSupport support) {
        support.judgeEnvironments.disposeCurrent();
        support.environments.stopKeeping();
        reconcileRemote(support);
    }

    /** Disposes a kept environment left by a previous instance ({@code --discard-work}, FR6). */
    static void disposeExistingEnvironment(ContainerRunSupport support) {
        support.environments.disposeExisting();
    }
}
