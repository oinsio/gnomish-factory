package com.github.oinsio.gnomish.adapter.git;

import com.github.oinsio.gnomish.app.git.TaskIdSanitizer;
import com.github.oinsio.gnomish.app.port.git.InvalidTaskIdException;
import com.github.oinsio.gnomish.gitobjects.ObjectId;
import java.nio.file.Path;

/**
 * Creates the task branch as a plain ref — {@code git branch <name> <start-point>}, never {@code
 * checkout -b} — so the clone's own checked-out branch, HEAD, and working tree stay untouched
 * (FR7: "the clone itself is untouched").
 *
 * <p><b>The start point is a commit this class never resolves</b> (FR15, design D12 of
 * add-base-ref-resolution, revised 2026-09-10). It arrives as an {@link ObjectId} the caller
 * peeled once — the law commit — and the only question left here is whether this repository holds
 * that object as a commit. No {@code rev-parse} of a base <em>name</em> runs, and none may: git
 * resolves a bare name through a fixed lookup order (gitrevisions) that reaches a stale local
 * branch and a planted local tag but never the {@code refs/remotes/origin/<n>} the refresh writes,
 * so a name here would let the branch start somewhere the frozen law never was. Nothing is ever
 * fetched or pulled — updating the clone is the human's job (design D7).
 *
 * <p>The returned {@link BranchCreationResult} distinguishes the three possible outcomes rather
 * than throwing, matching {@link GitProcessRunner}'s "expected git-level outcomes are results,
 * not exceptions" idiom: a caller such as the {@code TaskRepository} adapter needs to branch on
 * "already exists" (re-run with the same taskId) and "the clone holds no such commit" without
 * wrapping this call in try/catch.
 *
 * <p>Implements FR2, FR7 of add-git-workflow (design D7); FR15 of add-base-ref-resolution.
 */
public final class TaskBranchCreator {

    private final GitProcessRunner runner;

    public TaskBranchCreator(GitProcessRunner runner) {
        this.runner = runner;
    }

    /**
     * Creates the task branch for {@code taskId} in the clone at {@code cloneDir}.
     *
     * @param cloneDir the working directory of an existing git clone (the {@code --dir} target)
     * @param taskId the tracker's original taskId; sanitized via {@link
     *     TaskIdSanitizer#branchName}
     * @param lawCommit the commit to branch from — the caller's single peel; never fetched, never
     *     re-resolved, only verified to be a commit this repository holds
     * @return the outcome: the created branch's start commit, "already exists", or "the clone holds
     *     no such commit"
     * @throws InvalidTaskIdException if {@code taskId} cannot be sanitized into a safe branch name
     */
    public BranchCreationResult createBranch(Path cloneDir, String taskId, ObjectId lawCommit) {
        String branchName = TaskIdSanitizer.branchName(taskId);
        String baseCommit = lawCommit.hex();

        // Existence only: the object is already named, so this asks "does this repository hold it,
        // and is it a commit" and never "what does this string resolve to".
        GitCommandResult exists = runner.run(cloneDir, "cat-file", "-e", baseCommit + "^{commit}");
        if (exists.exitCode() != 0) {
            return new BranchCreationResult.BaseCommitMissing(baseCommit);
        }

        GitCommandResult branch = runner.run(cloneDir, "branch", branchName, baseCommit);
        if (branch.exitCode() != 0) {
            return new BranchCreationResult.AlreadyExists(branchName);
        }

        return new BranchCreationResult.Created(branchName, baseCommit);
    }
}
