package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * The second durable step of a replica-pair repair: brings a host-mode working tree to the tip its
 * ref was just moved to — tracked files via {@code reset --hard}, untracked leftovers of the old
 * line via {@code clean -fd} — and refuses the repair if either command fails.
 *
 * <p>{@link ReplicaPairReconciler} decides which tip wins; this executes that decision in the
 * working tree and owns the refusal when it cannot. Both exit codes are read: a half-resynced tree
 * is the same unclassifiable state as an unresynced one, since {@link WorktreeSalvage} would commit
 * whatever survives as the interrupted round's work — committing the discarded line straight back
 * on top of the adopted tip.
 *
 * <p>Implements FR8, NFR-R3 of harden-task-branch-contract.
 */
// Not a record: a behavior-bearing collaborator over the git seam, per this package's convention.
@SuppressWarnings("ClassCanBeRecord")
final class WorktreeResync {

    private final GitProcessRunner runner;
    private final Path worktreeRoot;

    /**
     * @param runner the shared git subprocess runner; never null
     * @param worktreeRoot the working tree to bring behind the moved ref; never null
     */
    WorktreeResync(GitProcessRunner runner, Path worktreeRoot) {
        this.runner = runner;
        this.worktreeRoot = worktreeRoot;
    }

    /**
     * @param taskId the task the branch belongs to, for the refusal message; never blank
     * @param branch the task branch name; never blank
     * @param localTip the tip the ref moved from
     * @param remoteTip the tip the ref moved to, and the tree is brought to
     * @throws IllegalStateException naming the branch, both tips, and the remedy
     */
    void resync(String taskId, String branch, String localTip, String remoteTip) {
        String context = "task " + taskId + ", branch " + branch + ": the local ref moved from " + localTip
                + " to origin's " + remoteTip + ", but the working tree at " + worktreeRoot
                + " still holds the old line";
        GitCommandResult reset = runner.run(worktreeRoot, "reset", "--hard", remoteTip);
        if (reset.exitCode() != 0) {
            throw failed(context, "git reset --hard " + remoteTip, reset);
        }
        GitCommandResult clean = runner.run(worktreeRoot, "clean", "-fd");
        if (clean.exitCode() != 0) {
            throw failed(context, "git clean -fd", clean);
        }
    }

    private static IllegalStateException failed(String context, String command, GitCommandResult result) {
        return new IllegalStateException(context + "; " + command + " failed (" + result.termination() + ", exit "
                + result.exitCode() + "): " + result.stderr().trim()
                + ". Continuing would let salvage commit the discarded line back on top of the adopted tip; run that"
                + " command in the working tree by hand, then resume the task.");
    }
}
