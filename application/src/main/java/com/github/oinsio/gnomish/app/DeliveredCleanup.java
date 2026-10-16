package com.github.oinsio.gnomish.app;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.LocalTaskBranch;
import com.github.oinsio.gnomish.app.port.git.TaskBranchGit;
import com.github.oinsio.gnomish.app.port.git.TaskGit;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleStore;
import com.github.oinsio.gnomish.app.port.run.SandboxRunSupport;
import com.github.oinsio.gnomish.app.port.tracker.TrackerTask;
import com.github.oinsio.gnomish.app.project.RegisteredClone;
import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal;
import com.github.oinsio.gnomish.app.take.FinishLanding;
import com.github.oinsio.gnomish.domain.branch.BranchShape;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one cleanup of a delivered branch (FR4, design D2, D5 of fix-terminal-receipt-convergence):
 * a tip that records {@code Completed} while it still carries {@code .gnomish-task/} — the {@code
 * CompletedUncleaned} shape — gets the cleanup commit through {@link
 * TaskLifecycleStore#finishCleanup} (pushed best-effort by the store's decorator) and then the
 * medium's disposal of the task's workspace. Every other tip is left alone: a {@code Delivered} tip
 * is already cleaned, and any other shape is not a delivery at all, so removing its envelope would
 * destroy a live task.
 *
 * <p>It is <em>not</em> a {@code TerminalEffect}: it writes no tracker effect, so there is nothing
 * to probe, and it checks and writes no claim — no {@code ClaimGuard}. Two entries:
 *
 * <ul>
 *   <li>{@link #cleanUp} for a caller whose finish is already settled — the destructive step a
 *       confirmed finish drive runs on a claimed {@code take}, or a manual {@code run} that has no
 *       tracker to ask;
 *   <li>{@link #cleanUpIfFinished} for a claimless caller that fetched the task: it cleans only
 *       when {@link FinishLanding} — the one finish reading — says the finish landed. The fetch and
 *       its failure handling stay the caller's, as {@link FinishLanding} prescribes.
 * </ul>
 *
 * <p>The tip is read once per call through {@link TaskBranchGit#classifyShape} — the branch
 * classifier at the task's registered commit, the same parse every take route reads — never
 * handed in by the caller, so no consumer can clean on a shape it decided for itself.
 *
 * <p>Crash consistency: the cleanup commit, then the disposal — destructive after constructive.
 * A kill between them leaves a {@code Delivered} tip beside a leftover workspace, which this owner
 * treats as done (a {@code Delivered} tip is a no-op) and the workspace janitor's age pass
 * converges. Idempotent: a second call reads {@code Delivered} and writes nothing. Two writers on
 * one tip race on the store's compare-and-swap; the loser's {@link GitTaskRepositoryException}
 * is recognised by the tip it leaves — {@code Delivered}, the winner's cleanup — and ends in one
 * DEBUG line, any other failure propagates (NFR-R1).
 *
 * <p>Implements FR4, FR8, NFR-R1, NFR-O1 of fix-terminal-receipt-convergence.
 */
public final class DeliveredCleanup {

    private static final Logger log = LoggerFactory.getLogger(DeliveredCleanup.class);

    private final TaskBranchGit branches;
    private final TaskLifecycleStore store;
    private final TaskEnvironmentDisposal disposal;

    /**
     * @param branches the branch port the tip is classified through; never null
     * @param store the medium's lifecycle store, which writes the cleanup commit and pushes it;
     *     never null
     * @param disposal the medium's workspace disposal, keyed by the sanitized task id; never null
     */
    public DeliveredCleanup(TaskBranchGit branches, TaskLifecycleStore store, TaskEnvironmentDisposal disposal) {
        this.branches = branches;
        this.store = store;
        this.disposal = disposal;
    }

    /**
     * The host medium: the registered clone's worktree-rooted store and its worktree disposal.
     *
     * @param git the task-git bundle the store, the branch port and the disposal come from; never null
     * @param clone the registered clone the task's worktree lives under; never null
     * @return the cleanup over the host medium; never null
     */
    public static DeliveredCleanup host(TaskGit git, RegisteredClone clone) {
        return new DeliveredCleanup(
                git.branches(),
                git.store().taskRepository(clone),
                git.worktrees().environmentDisposal(clone));
    }

    /**
     * The container medium: the support's bare-object store and its box disposal. The support is
     * bound to one task's box, so the key the disposal is handed names nothing it does not know.
     *
     * @param branches the branch port the tip is classified through; never null
     * @param support the task's sandbox run support; never null
     * @return the cleanup over the container medium; never null
     */
    public static DeliveredCleanup container(TaskBranchGit branches, SandboxRunSupport support) {
        return new DeliveredCleanup(
                branches, support.taskRepository(), ignoredKey -> support.disposeExistingEnvironment());
    }

    /**
     * Cleans the task's delivered branch when the tracker reports the task finished.
     *
     * @param cloneDir the clone holding the task branch; never null
     * @param branch the task's local branch, as the adoption owner answered it; never null
     * @param observed the task as the caller's own {@code fetchTask} reported it; never null
     */
    public void cleanUpIfFinished(Path cloneDir, LocalTaskBranch branch, TrackerTask observed) {
        if (!FinishLanding.landed(observed)) {
            log.debug(
                    "delivered cleanup of {} skipped: the tracker does not report the task finished", branch.taskId());
            return;
        }
        cleanUp(cloneDir, branch);
    }

    /**
     * Cleans the task's delivered branch; the caller's finish is already settled. The branch is
     * the {@link LocalTaskBranch} the adoption owner answered — a clone that holds the branch only
     * as a remote-tracking ref cannot be handed here, so the cleanup commit is always written to a
     * head that exists (design D10 of fix-terminal-receipt-convergence).
     *
     * @param cloneDir the clone holding the task branch; never null
     * @param branch the task's local branch, as the adoption owner answered it; never null
     */
    public void cleanUp(Path cloneDir, LocalTaskBranch branch) {
        String taskId = branch.taskId();
        BranchShape tip = branches.classifyShape(cloneDir, taskId);
        if (!(tip instanceof BranchShape.CompletedUncleaned)) {
            log.debug("delivered cleanup of {} is a no-op: the tip is {}", taskId, tip);
            return;
        }
        try {
            store.finishCleanup(taskId);
        } catch (GitTaskRepositoryException lost) {
            if (!(branches.classifyShape(cloneDir, taskId) instanceof BranchShape.Delivered)) {
                throw lost;
            }
            log.debug("delivered cleanup of {} lost the race: another writer landed the cleanup", taskId, lost);
            return;
        }
        disposal.dispose(TaskIdSanitizer.sanitize(taskId));
        log.info("delivered branch of {} cleaned up", taskId);
    }
}
