package com.github.oinsio.gnomish.app.port.git;

import java.nio.file.Path;
import java.util.List;

/**
 * The branch- and clone-level git capabilities a use case needs: harden a factory clone, find or
 * list task branches, read a branch's recorded state, and push a task branch best-effort. Every
 * operation takes the clone (or worktree) it acts on as an argument, so one bound instance serves
 * every task and every concurrent slot.
 *
 * <p>An {@code application}-owned port (FR12b, design D12 of split-into-modules): the use cases
 * used to construct the git-subprocess collaborators themselves, which bound them to the git
 * adapter. The adapter implements this interface; {@code bootstrap} binds it. Nothing here names a
 * git command, a ref format or a process — the vocabulary is task branches and recorded state, so a
 * non-git backend could satisfy the same contract.
 *
 * <p>Implements FR2, FR9, FR10, FR13 of add-git-workflow; FR12b of split-into-modules.
 */
public interface TaskBranchGit {

    /**
     * Applies the factory's required hardening to a clone before any task work touches it.
     *
     * @param cloneDir the working directory of an existing clone; never null
     */
    void harden(Path cloneDir);

    /**
     * Ensures a reconciled local branch for {@code taskId} exists in {@code cloneDir}, without
     * checking anything out: the branch is looked up locally, then as a remote-tracking ref, then
     * fetched narrowly; a local branch behind the delivered one is fast-forwarded, ahead is kept.
     * The ref-only path a container-mode resume takes — it has no worktree (FR6, FR17 of
     * add-sandbox-core).
     *
     * @param cloneDir the clone to reconcile in; never null
     * @param taskId the tracker's original taskId; never null
     * @return {@code true} when the branch exists locally after this call; {@code false} when it
     *     exists nowhere
     * @throws DivergedBranchException if the local and delivered tips share no ancestry
     */
    boolean ensureLocalTaskBranch(Path cloneDir, String taskId);

    /**
     * Finds where {@code taskId}'s branch lives — locally, only as a remote-tracking ref, or
     * nowhere.
     *
     * @param cloneDir the clone to search; never null
     * @param taskId the tracker's original taskId; never null
     * @return the branch's location; never null
     */
    BranchLocation locate(Path cloneDir, String taskId);

    /**
     * Lists one row per task branch reachable from {@code cloneDir}, for {@code gnomish status}.
     *
     * @param cloneDir the clone to list; never null
     * @return the rows, possibly empty; never null
     */
    List<TaskListRow> list(Path cloneDir);

    /**
     * Reads {@code taskId}'s recorded branch state without checking anything out.
     *
     * @param cloneDir the clone to read from; never null
     * @param taskId the tracker's original taskId; never null
     * @return the branch state result; never null
     */
    BranchStateResult readState(Path cloneDir, String taskId);

    /**
     * Reads the delivered (remote-tracking) state of {@code taskId}'s branch, for reconcile.
     *
     * @param cloneDir the clone to read from; never null
     * @param taskId the tracker's original taskId; never null
     * @return the delivered branch state; never null
     */
    DeliveredBranchState readDelivered(Path cloneDir, String taskId);

    /**
     * Pushes {@code branch} best-effort: a push failure is swallowed, never propagated, because
     * the durable record is the local branch and the push is an availability optimization.
     *
     * @param worktreeRoot the worktree to push from; never null
     * @param branch the task branch name; never null
     */
    void pushBestEffort(Path worktreeRoot, String branch);

    /**
     * Brings the remote up to {@code taskId}'s local branch tip when it is behind or does not
     * carry the branch at all — the touchpoint check that delivers a push an earlier run lost to a
     * crash or an outage, whichever instance next touches the task (FR3 of fix-lifecycle-push).
     * Best-effort like {@link #pushBestEffort}: it never blocks, fails, or throws, and with no
     * remote configured it does nothing at all.
     *
     * @param cloneDir the clone the branch and its recorded tip live in; never null
     * @param taskId the tracker's original taskId; never blank
     * @param touchpoint what triggered the check, for log context (e.g. {@code resume-start},
     *     {@code terminal-boundary}); never blank
     */
    void reconcileRemote(Path cloneDir, String taskId, String touchpoint);

    /**
     * Verifies {@code taskId}'s branch tip is on the remote before a park's terminal tracker write,
     * delivering it with one bounded re-attempt when it is not (FR4 of fix-lifecycle-push) — so the
     * tracker never announces a park whose commit the remote lacks. Never blocks or fails the park:
     * a failure comes back as {@link ParkDeliveryVerdict.Undelivered} carrying the line the human
     * reading the park needs (FR5). With no remote configured the verdict is {@code Delivered} and
     * no remote interaction happens at all.
     *
     * @param cloneDir the clone the branch lives in; never null
     * @param taskId the parking task's tracker id; never blank
     * @return the delivery verdict; never null
     */
    ParkDeliveryVerdict fenceParkDelivery(Path cloneDir, String taskId);
}
