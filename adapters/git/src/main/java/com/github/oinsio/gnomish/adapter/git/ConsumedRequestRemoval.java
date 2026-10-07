package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.port.git.GitTaskRepositoryException;
import com.github.oinsio.gnomish.app.port.git.TaskLifecycleEvent;
import com.github.oinsio.gnomish.gitobjects.TreeEdit;
import java.nio.file.Path;

/**
 * The one removal of consumed decision requests from a task branch, for both media (design D7, the
 * "three outcome-clearing writes" row, and the hygiene part of D10 of make-checkpoint-gate-durable):
 * {@code appendDecision}, {@code approveCheckpoint} and {@code resumeFrom} each consume whatever
 * the tip recorded, so each drops the gnome-writable {@code decisions/} subtree in the commit that
 * consumes it — a branch under escalation does not accumulate answered questions.
 *
 * <p>Hygiene, not judgement: no reader relies on the removal. A request is live only under its
 * round's token, so a kill between an answer and its push, or a tip that never lost the directory,
 * changes nothing a reader decides. That is also why the removal is unconditional and quiet: a tip
 * that carries no {@code decisions/} entry writes exactly as before.
 *
 * <p>Crash consistency ({@code .claude/rules/crash-consistency.md}, item 4): the removal is staged
 * into the outcome-clearing write's own commit, never a commit of its own, so no kill window
 * freezes a tip with the outcome cleared and the request still present, or the reverse. {@link
 * CleanupCommit} stays the terminal sweep of the whole envelope; this class is the second remover,
 * scoped to the subtree a consuming write has made obsolete.
 *
 * <p>The subtree's spelling is not owned here: it is {@link FactoryOwnedPaths#GNOME_WRITABLE},
 * itself derived from the one owner of the envelope's paths.
 *
 * <p>Implements FR14 of make-checkpoint-gate-durable.
 */
final class ConsumedRequestRemoval {

    private ConsumedRequestRemoval() {}

    /**
     * The bare-object medium's removal: one tree edit dropping the subtree, appended to the
     * consuming write's own edits. Deleting a path the parent tree does not hold is a no-op of the
     * commit builder, so a tip without the directory commits only the envelope edits.
     *
     * @return the tree edit removing {@code decisions/}
     */
    static TreeEdit treeEdit() {
        return new TreeEdit.DeletePath(FactoryOwnedPaths.GNOME_WRITABLE);
    }

    /**
     * The worktree medium's removal: drops every index entry under {@code decisions/} — and the
     * files with it — after the consuming write has staged its envelope, so the commit that follows
     * carries the removal. {@code -f} because a staged addition or a locally modified request is
     * still a consumed one; {@code --ignore-unmatch} because a tip and worktree with no request are
     * the ordinary case, not a failure.
     *
     * @param runner the git subprocess runner
     * @param worktree the task worktree whose index the removal is staged into
     * @param taskId the task being written; for error reporting
     * @param event the consuming write's lifecycle event; for error reporting
     */
    static void stage(GitProcessRunner runner, Path worktree, String taskId, TaskLifecycleEvent event) {
        GitCommandResult rm = runner.run(
                worktree, "rm", "-r", "-f", "-q", "--ignore-unmatch", "--", FactoryOwnedPaths.GNOME_WRITABLE);
        if (rm.exitCode() != 0) {
            throw new GitTaskRepositoryException(
                    taskId, event, "git rm -r " + FactoryOwnedPaths.GNOME_WRITABLE, rm.stderr());
        }
    }
}
