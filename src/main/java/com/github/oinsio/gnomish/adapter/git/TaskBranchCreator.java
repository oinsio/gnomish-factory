package com.github.oinsio.gnomish.adapter.git;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Creates the task branch as a plain ref — {@code git branch <name> <start-point>}, never {@code
 * checkout -b} — so the clone's own checked-out branch, HEAD, and working tree stay untouched
 * (FR7: "the clone itself is untouched"). The branch's starting point is the clone's current
 * {@code HEAD} by default; an explicit {@code baseRef} (branch, tag, or commit-ish) overrides it.
 * Neither path ever fetches or pulls — updating the clone is the human's job (design D7).
 *
 * <p>The returned {@link BranchCreationResult} distinguishes the three possible outcomes rather
 * than throwing, matching {@link GitProcessRunner}'s "expected git-level outcomes are results,
 * not exceptions" idiom: a caller such as the {@code TaskRepository} adapter (task 3.4) needs to
 * branch on "already exists" (re-run with the same taskId) and "base ref didn't resolve" (bad
 * {@code --base}) without wrapping this call in try/catch.
 *
 * <p>Implements FR2, FR7 of add-git-workflow (design D7).
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
     * @param baseRef when non-null, the ref (branch/tag/commit-ish) to branch from instead of the
     *     clone's current {@code HEAD}; never fetched or pulled, must already resolve locally
     * @return the outcome: the created branch's base commit SHA, "already exists", or "base ref
     *     did not resolve"
     * @throws InvalidTaskIdException if {@code taskId} cannot be sanitized into a safe branch name
     */
    public BranchCreationResult createBranch(Path cloneDir, String taskId, @Nullable String baseRef) {
        String branchName = TaskIdSanitizer.branchName(taskId);

        GitCommandResult resolve = runner.run(cloneDir, "rev-parse", "--verify", startPoint(baseRef));
        if (resolve.exitCode() != 0) {
            return new BranchCreationResult.BaseRefNotResolved(baseRef);
        }
        String baseCommit = resolve.stdout().trim();

        GitCommandResult branch = runner.run(cloneDir, "branch", branchName, baseCommit);
        if (branch.exitCode() != 0) {
            return new BranchCreationResult.AlreadyExists(branchName);
        }

        return new BranchCreationResult.Created(branchName, baseCommit);
    }

    private static String startPoint(@Nullable String baseRef) {
        return baseRef != null ? baseRef : "HEAD";
    }
}
