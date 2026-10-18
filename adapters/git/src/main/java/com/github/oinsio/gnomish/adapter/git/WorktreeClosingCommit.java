package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.List;

/**
 * A host-mode closing commit — a round, a lifecycle transition, a salvage, the cleanup — made from
 * a task worktree's index and landed with its register in one ref transaction (design D13 of
 * tighten-spec-contract, amended 2026-10-13). Where {@code git commit} moved the branch first and
 * left the register to a second step, this writes the commit as an object ({@code write-tree},
 * {@code commit-tree}) and hands it to {@link FactoryRegister#close}, which moves the branch and the
 * register together: a kill anywhere before the transaction leaves an unreferenced object and both
 * refs where they were; after it, both name the commit.
 *
 * <p>The commit's parent is the task branch's tip, read under the compare-and-swap the transaction
 * then holds it to. The worktree stays consistent behind the transaction without a checkout: its
 * {@code HEAD} is the symbolic ref of the branch, so it follows; the index is the tree just
 * committed, and the working tree holds what was staged. A host gnome that switched its worktree to
 * another branch leaves {@code HEAD} there, as the evidence of what it did, and the closing commit
 * — an {@code Aborted} outcome, typically — still lands on the task branch, where every reader
 * looks. The steps run no hook and read the committer identity from the git configuration, exactly
 * as {@code git commit} does; an index whose tree equals the parent's is refused as "nothing to
 * commit", as {@code git commit} refuses it.
 *
 * <p>Implements FR13, NFR-S2 of tighten-spec-contract.
 */
@UntrustedParser
final class WorktreeClosingCommit {

    /** What one closing commit came to: the commit that landed, or the step that failed. */
    sealed interface Outcome {

        /** Both refs name {@code commit}. */
        record Landed(String commit) implements Outcome {}

        /** {@code step} failed; neither ref moved. */
        record Failed(String step, UntrustedText evidence) implements Outcome {}
    }

    private final GitRunner runner;
    private final FactoryRegister register;

    WorktreeClosingCommit(GitRunner runner) {
        this.runner = runner;
        this.register = new FactoryRegister(runner);
    }

    /**
     * Commits {@code worktree}'s index with {@code message} on the task branch and lands the commit
     * with its register.
     *
     * @param worktree the task worktree, its index already staged
     * @param taskId the tracker's original taskId; names the branch and the register
     * @param message the commit message, the claim-epoch trailer already stamped
     * @return the commit that landed, or the step that failed
     */
    Outcome commit(Path worktree, String taskId, String message) {
        String branchRef = TaskBranchRefs.head(taskId);
        GitCommandResult base = runner.run(worktree, "rev-parse", branchRef, branchRef + "^{tree}");
        if (!base.succeeded()) {
            return new Outcome.Failed("git rev-parse " + branchRef, base.failureDetail("task branch read"));
        }
        // @UntrustedParser warrant (design D11): two object ids git printed for the factory's own
        //     revisions, each refused unless it is one full object id (FR17 of
        //     fix-terminal-receipt-convergence), used as a parent argument and an equality subject.
        List<String> ids = base.stdout().forParsing().strip().lines().toList();
        if (ids.size() != 2 || !ids.stream().allMatch(VerifiedTip::isObjectId)) {
            return malformed("git rev-parse " + branchRef);
        }
        GitCommandResult tree = runner.run(worktree, "write-tree");
        if (!tree.succeeded()) {
            return new Outcome.Failed("git write-tree", tree.failureDetail("index write"));
        }
        String treeId = tree.stdout().forParsing().strip();
        if (!VerifiedTip.isObjectId(treeId)) {
            return malformed("git write-tree");
        }
        if (treeId.equals(ids.get(1))) {
            return new Outcome.Failed("git commit", UntrustedText.factory("nothing to commit in " + worktree));
        }
        String parent = ids.getFirst();
        GitCommandResult commit = runner.run(worktree, "commit-tree", treeId, "-p", parent, "-m", message);
        if (!commit.succeeded()) {
            return new Outcome.Failed("git commit-tree", commit.failureDetail("commit write"));
        }
        String commitId = commit.stdout().forParsing().strip();
        if (!VerifiedTip.isObjectId(commitId)) {
            return malformed("git commit-tree");
        }
        GitCommandResult landed = register.close(worktree, taskId, commitId, parent);
        if (!landed.succeeded()) {
            return new Outcome.Failed("closing ref transaction", landed.failureDetail("ref transaction"));
        }
        return new Outcome.Landed(commitId);
    }

    /**
     * A step that exited zero without printing the full object ids it owes: a named failure that
     * lands nothing, never a parent, a tree or a commit built from a prefix.
     */
    private static Outcome malformed(String step) {
        return new Outcome.Failed(
                step, UntrustedText.factory(step + " exited 0 without printing a full object id; nothing landed"));
    }
}
