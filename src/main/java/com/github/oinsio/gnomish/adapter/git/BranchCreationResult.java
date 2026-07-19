package com.github.oinsio.gnomish.adapter.git;

import org.jspecify.annotations.Nullable;

/**
 * The outcome of {@link TaskBranchCreator#createBranch}: exactly one of a successful creation
 * (with the resolved base commit SHA the caller records into {@code task.json}'s {@code
 * baseCommit}), the branch already existing, or a supplied {@code --base} ref that did not
 * resolve. Modeled as a sealed interface rather than a thrown exception because all three are
 * expected, caller-decidable outcomes (re-running a task, a typo'd {@code --base}), not defects.
 *
 * <p>Implements FR2, FR7 of add-git-workflow (design D7).
 */
public sealed interface BranchCreationResult {

    /**
     * The branch was created.
     *
     * @param branchName the created branch's full name, e.g. {@code gnomish/PROJ-42}
     * @param baseCommit the full commit SHA the branch was created from
     */
    record Created(String branchName, String baseCommit) implements BranchCreationResult {}

    /**
     * A branch with this name already existed; nothing was created or overwritten.
     *
     * @param branchName the branch name that was already taken
     */
    record AlreadyExists(String branchName) implements BranchCreationResult {}

    /**
     * The supplied {@code --base} ref did not resolve to a commit in the clone; nothing was
     * created. Never fetched to try to resolve it further (design D7).
     *
     * @param baseRef the ref, as supplied by the caller, that failed to resolve
     */
    record BaseRefNotResolved(@Nullable String baseRef) implements BranchCreationResult {}
}
