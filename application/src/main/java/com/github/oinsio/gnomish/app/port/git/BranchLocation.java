package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;

/**
 * The outcome of {@code TaskBranchLocator#locate}: exactly one of a local branch, a
 * remote-tracking branch (already present or just narrow-fetched — callers do not need to tell
 * these apart, both are read the same way), "not found anywhere", or "origin never answered".
 * Modeled as a sealed interface rather than a thrown exception because all four are expected,
 * caller-decidable outcomes (a healthy task branch, a peer instance's in-progress task, a
 * merged-and-deleted branch, an origin that could not be asked), not defects — matching the
 * {@code BranchCreationResult} precedent in the adapter that implements this port.
 *
 * <p>Both {@link Local#ref()} and {@link RemoteTracking#ref()} are fully-qualified refs ({@code
 * refs/heads/...} / {@code refs/remotes/origin/...}) rather than short names, so a caller can feed
 * either one directly into {@code git show <ref>:<path>} or {@code git worktree add <path> <ref>}
 * without first having to know which variant it received.
 *
 * <p>{@link NotFound} and {@link Unavailable} are the two halves of what was one outcome before
 * FR6 of harden-task-branch-contract: absence is a fact only origin can state, so a lookup that
 * never got an answer says so instead of borrowing absence's name — routing a duplicate branch
 * into existence is exactly what the conflation caused. {@link Refused} is the third failure,
 * split from {@link Unavailable} by FR5 of own-git-transfer-argv: origin answered and served the
 * branch, and git's own object validation would not accept what arrived — a fact about the
 * repository, never about the daemon.
 *
 * <p>Implements FR8, FR13 of add-git-workflow; FR6 of harden-task-branch-contract; FR5 of
 * own-git-transfer-argv.
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
     * <p>The reason quotes git's own stderr, so it is {@link UntrustedText} rather than a
     * {@code String}: a remote speaks through that stream, and the sentence travels into
     * {@link BranchLocationUnavailableException}'s message, a log record and the abort diagnosis.
     * Carrying it typed is what keeps every one of those sinks taking an exit instead of the raw
     * bytes (FR1, FR2 of type-untrusted-text) — the same shape {@link DefaultBranchDiscovery} and
     * {@link BaseRefreshOutcome} already use in this package.
     *
     * @param reason what stopped the lookup, for the abort diagnosis and the repair log;
     *     credentials already scrubbed
     */
    record Unavailable(UntrustedText reason) implements BranchLocation {}

    /**
     * Origin served the task branch and the fetch refused it: an object in the branch's history
     * failed git's validation, so the branch cannot be read into this clone at all. A task-level
     * refusal — the next fetch reads the same objects, so no retry and no infrastructure budget
     * applies; the task parks with the report for a human, and the outage accounting never sees it
     * (NFR-R1 of own-git-transfer-argv).
     *
     * @param report one operator-facing paragraph naming the branch, the validation message id and
     *     the object git refused; carried as {@link UntrustedText} because the id and the object
     *     are git's words about content a gnome or a remote authored, quoted through an exit
     */
    record Refused(UntrustedText report) implements BranchLocation {}
}
