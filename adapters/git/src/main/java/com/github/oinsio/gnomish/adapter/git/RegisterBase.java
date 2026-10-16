package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;

/**
 * The base a host-mode closing write builds on: the file or tree entry at the task's
 * <b>registered commit</b>, read by the writer that is about to commit over it (design D18 of
 * tighten-spec-contract). Not a reader of task-branch state — no pickup decision is made from what
 * it returns. A lifecycle write reads {@code task.json} here to rewrite it ({@link RequiredTaskJson},
 * {@link RequiredTaskState}), the state write the denial cursor it carries forward ({@link
 * StateFileWrite}), and the salvage whether there is a state directory to restore ({@link
 * WorktreeSalvage}). The cleanup commit is not among them: it is written over bare objects at the
 * branch tip ({@link GitObjectsTerminalCommits#cleanUp}, design D8 of
 * fix-terminal-receipt-convergence).
 *
 * <p>The register, not the worktree's {@code HEAD}: in host mode the gnome commits on the branch
 * between closing commits, so {@code HEAD} may carry an envelope it shaped, and a closing commit
 * built on that would launder it into the factory's record. The writers restore the factory-owned
 * paths from the same commit before they stage ({@link EnvelopeRestore}), so what a closing commit
 * carries of the factory's is the register's, whatever the gnome committed above it. Every pickup
 * decision reads at the registered commit too ({@link GitShowTip}, design D13).
 *
 * <p>The answers pass the same gate as every revision-scoped read: an invocation cut off before its
 * own exit throws {@link BranchTipUnavailableException} rather than answering "absent" (NFR-R2 of
 * fix-envelope-medium).
 *
 * <p>Implements FR2, FR4, NFR-R2 of fix-envelope-medium; FR13, NFR-S2 of tighten-spec-contract.
 */
final class RegisterBase {

    private RegisterBase() {}

    /**
     * The read at the registered commit, with git's own refusal kept: a revision with no such path,
     * a missing register and a corrupt object all exit non-zero and say which in {@code stderr}.
     *
     * @param runner the git subprocess seam
     * @param worktree the task worktree; the ref store is the clone's
     * @param taskId the tracker's original taskId; names the register
     * @param path the repository-relative path to read
     * @return the invocation's own result, exit code and captured streams included
     */
    static GitCommandResult show(GitRunner runner, Path worktree, String taskId, String path) {
        String register = FactoryRegisterRefs.of(taskId).name();
        return GitReadGate.answered(register, "show", runner.run(worktree, "show", register + ":" + path));
    }

    /**
     * Whether the registered commit's tree carries an entry at {@code path} — {@code git cat-file
     * -e}, which exits 0 when it exists, without writing its content.
     *
     * @param runner the git subprocess seam
     * @param worktree the task worktree
     * @param taskId the tracker's original taskId; names the register
     * @param path the repository-relative path to test, e.g. {@code .gnomish-task}
     * @return {@code true} when the registered commit's tree carries an entry at that path
     */
    static boolean carries(GitRunner runner, Path worktree, String taskId, String path) {
        String register = FactoryRegisterRefs.of(taskId).name();
        GitCommandResult result = GitReadGate.answered(
                register, "cat-file", runner.run(worktree, "cat-file", "-e", register + ":" + path));
        return result.exitCode() == 0;
    }
}
