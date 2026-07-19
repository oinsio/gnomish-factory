package com.github.oinsio.gnomish.adapter.git;

/**
 * The outcome of {@link TaskBranchLocator#locate}: exactly one of a local branch, a
 * remote-tracking branch (already present or just narrow-fetched — callers do not need to tell
 * these apart, both are read the same way), or "not found anywhere". Modeled as a sealed
 * interface rather than a thrown exception because all three are expected, caller-decidable
 * outcomes (a healthy task branch, a peer instance's in-progress task, a merged-and-deleted
 * branch), not defects — matching the {@link BranchCreationResult} precedent in this package.
 *
 * <p>Both {@link Local#ref()} and {@link RemoteTracking#ref()} are fully-qualified refs ({@code
 * refs/heads/...} / {@code refs/remotes/origin/...}) rather than short names, so a caller can feed
 * either one directly into {@code git show <ref>:<path>} or {@code git worktree add <path> <ref>}
 * without first having to know which variant it received.
 *
 * <p>Implements FR8, FR13 of add-git-workflow.
 */
public sealed interface BranchLocation {

    /**
     * The task branch exists as a local branch in the clone; no fetch was performed.
     *
     * @param ref the fully-qualified local ref, e.g. {@code refs/heads/gnomish/PROJ-42}
     */
    record Local(String ref) implements BranchLocation {}

    /**
     * The task branch exists only as a remote-tracking ref — either it was already present
     * (fetched by a prior run) or {@link TaskBranchLocator#locate} performed the narrow fetch
     * itself; callers cannot tell these apart from this result alone and, per FR8/FR13, do not
     * need to.
     *
     * @param ref the fully-qualified remote-tracking ref, e.g. {@code
     *     refs/remotes/origin/gnomish/PROJ-42}
     */
    record RemoteTracking(String ref) implements BranchLocation {}

    /**
     * The task branch exists neither locally, nor as a remote-tracking ref, nor on {@code origin}
     * — including after the narrow fetch attempt failed (no such ref, no remote configured,
     * network unreachable). A legitimate outcome (e.g. a merged PR's branch was deleted), not a
     * defect.
     */
    record NotFound() implements BranchLocation {}
}
