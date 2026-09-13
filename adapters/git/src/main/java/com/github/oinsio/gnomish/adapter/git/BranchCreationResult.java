package com.github.oinsio.gnomish.adapter.git;

/**
 * The outcome of {@link TaskBranchCreator#createBranch}: exactly one of a successful creation
 * (with the start-point commit the caller records into {@code task.json}'s {@code baseCommit}),
 * the branch already existing, or a start-point commit this repository does not hold. Modeled as a
 * sealed interface rather than a thrown exception because all three are expected,
 * caller-decidable outcomes (re-running a task, a clone that never received the object), not
 * defects.
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
     * The start point is not a commit this repository holds; nothing was created. Never fetched to
     * try to obtain it (design D7): by the time a commit reaches this port its caller has already
     * established it — the refresh delivered it, or the manual tier read it out of this very
     * repository — so a missing object here means the clone was changed underneath the run
     * (FR15 of add-base-ref-resolution).
     *
     * @param baseCommit the start-point commit, as supplied by the caller, that the repository
     *     holds no commit object for
     */
    record BaseCommitMissing(String baseCommit) implements BranchCreationResult {}
}
