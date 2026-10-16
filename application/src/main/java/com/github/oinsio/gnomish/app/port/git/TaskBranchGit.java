package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.domain.branch.BranchShape;
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
 * <p>Implements FR2, FR9, FR10, FR13 of add-git-workflow; FR12b of split-into-modules; FR7, FR11 of
 * fix-terminal-receipt-convergence.
 */
public interface TaskBranchGit {

    /**
     * Applies the factory's required hardening to a clone before any task work touches it, after
     * refusing a clone the factory cannot write atomically to.
     *
     * @param cloneDir the working directory of an existing clone; never null
     * @throws CloneRefFormatRefusedException when the clone is not on the ref backend whose ref
     *     transactions are atomic on disk (design D13 of tighten-spec-contract); nothing is written
     */
    void harden(Path cloneDir);

    /**
     * Ensures a reconciled local branch for {@code taskId} exists in {@code cloneDir}, without
     * checking anything out: the branch is looked up locally, then as a remote-tracking ref, then
     * fetched narrowly. A local branch behind the delivered one is fast-forwarded, ahead is kept; a
     * branch held only as a remote-tracking ref is <em>adopted</em> — its head created at the
     * tracking tip by one ref transaction under a compare-and-swap on absence, the clone's register
     * untouched — and an adoption lost to another adopter answers already local (design D9, D10 of
     * fix-terminal-receipt-convergence). The one way a remote-tracking-only branch becomes local.
     *
     * @param cloneDir the clone to reconcile in; never null
     * @param taskId the tracker's original taskId; never null
     * @return {@link BranchAdoption.Adopted} at the tip this call created the head at, {@link
     *     BranchAdoption.AlreadyLocal}, or {@link BranchAdoption.Nowhere} when the branch exists
     *     nowhere; the first two are the {@link LocalTaskBranch} a writer's entry accepts
     * @throws BranchLocationUnavailableException if the lookup could not reach origin
     * @throws BranchLocationRefusedException if git's object validation refused the branch
     * @throws DivergedBranchException if the local and delivered tips share no ancestry
     *     relationship and no claim is held on the task — the discard that resolves divergence is
     *     the claim protocol's arbitration (FR8 of harden-task-branch-contract)
     */
    BranchAdoption ensureLocalTaskBranch(Path cloneDir, String taskId);

    /**
     * Adopts {@code taskId}'s branch over the location the caller's own {@link #locate} just
     * answered — the same owner and the same ref transaction as {@link #ensureLocalTaskBranch},
     * with no second lookup (design D9, D11 of fix-terminal-receipt-convergence): a caller that
     * already located the branch, so its touchpoint fetch has already refreshed the tracking ref
     * and the register, adopts over that answer rather than asking origin twice. A local branch
     * answers already local without being reconciled; the reconcile stays the caller's.
     *
     * @param cloneDir the clone the branch was located in; never null
     * @param taskId the tracker's original taskId; never null
     * @param location what {@link #locate} answered for {@code taskId} in {@code cloneDir}; never
     *     null
     * @return adopted at the tracking tip, already local, or nowhere for {@link
     *     BranchLocation.NotFound}
     * @throws BranchLocationUnavailableException for {@link BranchLocation.Unavailable}
     * @throws BranchLocationRefusedException for {@link BranchLocation.Refused}
     */
    BranchAdoption adopt(Path cloneDir, String taskId, BranchLocation location);

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
     * Classifies {@code taskId}'s branch tip into exactly one {@linkplain
     * com.github.oinsio.gnomish.domain.branch.BranchShape shape} — the one question every take,
     * resume and reconcile path asks before it decides what to do (FR1, FR2). The classification
     * reads the tip, never a working copy, so a crashed instance's half-written files cannot
     * influence it (FR5).
     *
     * @param cloneDir the clone the branch lives in; never null
     * @param taskId the tracker's original taskId; never null
     * @return the tip's shape; never null and never a thrown content failure
     * @throws BranchLocationUnavailableException when the lookup could not establish whether the
     *     branch exists at all (FR6)
     * @throws BranchTipUnavailableException when the branch was located but a read of its tip did
     *     not run to its own exit, so no fact about the tip was established (FR6)
     */
    BranchShape classifyShape(Path cloneDir, String taskId);

    /**
     * Classifies {@code taskId}'s branch tip over the location the caller's own {@link #locate}
     * just answered — the same classification as {@link #classifyShape(Path, String)}, with no
     * second lookup (design D13 of fix-terminal-receipt-convergence, as amended for task 6.6): a
     * caller that decides on the tip, then adopts over the location and names it, reads all three
     * from one lookup, and pays one touchpoint fetch. The two-argument form is this read over its
     * own {@link #locate}, so a location has one parse.
     *
     * @param cloneDir the clone the branch was located in; never null
     * @param taskId the tracker's original taskId; never null
     * @param location what {@link #locate} answered for {@code taskId} in {@code cloneDir}; never
     *     null
     * @return the tip's shape — {@link BranchShape.Bare} for {@link BranchLocation.NotFound}; never
     *     null
     * @throws BranchLocationUnavailableException for {@link BranchLocation.Unavailable} (FR6)
     * @throws BranchTipUnavailableException when a read of the located tip did not run to its own
     *     exit (FR6)
     */
    BranchShape classifyShape(Path cloneDir, String taskId, BranchLocation location);

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
     * Pushes {@code taskId}'s branch and its factory register best-effort, as the clone holds them,
     * in one atomic push (design D13 of tighten-spec-contract) — after a salvage, which registered
     * its own commit when it made one, so a tip the gnome left is never registered here. A push
     * failure is swallowed, never propagated, because the durable record is the local branch and
     * the push is an availability optimization.
     *
     * @param worktreeRoot the worktree to push from; never null
     * @param taskId the tracker's original taskId, naming the branch and the register; never null
     */
    void pushClosingBestEffort(Path worktreeRoot, String taskId);

    /**
     * Brings the remote up to {@code taskId}'s local branch tip when it is behind or does not
     * carry the branch at all — the touchpoint check that delivers a push an earlier run lost to a
     * crash or an outage, whichever instance next touches the task (FR3 of fix-lifecycle-push).
     * Best-effort like {@link #pushClosingBestEffort}: it never blocks, fails, or throws, and with no
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
