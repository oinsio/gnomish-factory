package com.github.oinsio.gnomish.app.port.git;

/**
 * The outcome of {@code TaskBranchLocator#locate}: exactly one of a local branch, a
 * remote-tracking branch (already present or just narrow-fetched — callers do not need to tell
 * these apart, both are read the same way), "not found anywhere", or "origin never answered".
 * Modeled as a sealed interface rather than a thrown exception because all three are expected,
 * caller-decidable
 * outcomes (a healthy task branch, a peer instance's in-progress task, a merged-and-deleted
 * branch), not defects — matching the {@code BranchCreationResult} precedent in the adapter that
 * implements this port.
 *
 * <p>Both {@link Local#ref()} and {@link RemoteTracking#ref()} are fully-qualified refs ({@code
 * refs/heads/...} / {@code refs/remotes/origin/...}) rather than short names, so a caller can feed
 * either one directly into {@code git show <ref>:<path>} or {@code git worktree add <path> <ref>}
 * without first having to know which variant it received.
 *
 * <p>{@link NotFound} and {@link Unavailable} are the two halves of what was one outcome before
 * FR6 of harden-task-branch-contract: absence is a fact only origin can state, so a lookup that
 * never got an answer says so instead of borrowing absence's name — routing a duplicate branch
 * into existence is exactly what the conflation caused.
 *
 * <p>Implements FR8, FR13 of add-git-workflow; FR6 of harden-task-branch-contract.
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
     * (fetched by a prior run) or {@code TaskBranchLocator#locate} performed the narrow fetch
     * itself; callers cannot tell these apart from this result alone and, per FR8/FR13, do not
     * need to.
     *
     * @param ref the fully-qualified remote-tracking ref, e.g. {@code
     *     refs/remotes/origin/gnomish/PROJ-42}
     */
    record RemoteTracking(String ref) implements BranchLocation {}

    /**
     * The task branch exists neither locally, nor as a remote-tracking ref, nor on {@code origin}
     * — and {@code origin} itself confirmed the ref is missing (or there is no {@code origin} to
     * ask, so the clone's own refs are the whole truth). A legitimate outcome (e.g. a merged PR's
     * branch was deleted), not a defect.
     */
    record NotFound() implements BranchLocation {}

    /**
     * The lookup could not establish whether the branch exists: the narrow fetch did not run to
     * its own exit, or it failed while {@code origin} could not be asked to confirm. Never
     * equivalent to {@link NotFound} — a caller that routes this to a fresh claim forks a second
     * branch for a task that already has one (FR6).
     *
     * @param reason what stopped the lookup, for the abort diagnosis and the repair log
     */
    record Unavailable(String reason) implements BranchLocation {}
}
