package com.github.oinsio.gnomish.app.port.git;

/**
 * The answer of {@link TaskBranchGit#ensureLocalTaskBranch}, the one way a task branch a clone
 * holds only as a remote-tracking ref becomes local (design D9, D10 of
 * fix-terminal-receipt-convergence): adopted, already local, or nowhere.
 *
 * <p>The two answers that leave a local branch behind are the {@link LocalTaskBranch} view — what
 * a writer's entry accepts — so a caller holding {@link Nowhere} has nothing to hand a writer, and
 * "a clone with no local branch" is a state no writer can be given. {@link Adopted} names a head
 * this call created; nothing undoes it (design D12, NG4).
 *
 * <p>Implements FR7, FR8 of fix-terminal-receipt-convergence.
 */
public sealed interface BranchAdoption permits LocalTaskBranch, BranchAdoption.Nowhere {

    /**
     * The clone held the branch only as a remote-tracking ref, and this call created its head at
     * the tracking tip by one compare-and-swap on absence; the clone's register was not moved.
     *
     * @param taskId the tracker's original taskId; never null
     */
    record Adopted(String taskId) implements LocalTaskBranch {}

    /**
     * The branch was already local — found so, or created by another adopter whose {@code create}
     * landed first; this call wrote no ref of its own.
     *
     * @param taskId the tracker's original taskId; never null
     */
    record AlreadyLocal(String taskId) implements LocalTaskBranch {}

    /** The branch exists neither locally nor as a remote-tracking ref, and origin confirmed it; no ref was written. */
    record Nowhere() implements BranchAdoption {}
}
