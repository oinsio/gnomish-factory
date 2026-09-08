package com.github.oinsio.gnomish.app.port.git;

/**
 * What one bounded remote read established about the repository's default branch — the
 * zero-configuration tier of base resolution (FR5).
 *
 * <p>Four arms, not an {@code Optional}, for the reason the adapter's {@code RemoteBranchTip.Carriage} exists:
 * "origin says its default branch is {@code develop}", "there is no origin to ask", "origin
 * answered and named none" and "origin never answered" are four different facts, and only the last
 * is an infrastructure failure the daemon is charged for. Collapsing any pair of them is how a
 * guessed {@code main} would reach a task branch.
 *
 * <p>Implements FR5, FR9 of add-base-ref-resolution.
 */
public sealed interface DefaultBranchDiscovery {

    /**
     * Origin answered and named its default branch.
     *
     * @param branch the short branch name, {@code refs/heads/} stripped
     */
    record Discovered(String branch) implements DefaultBranchDiscovery {}

    /**
     * The clone has no {@code origin} remote, so there is no one to ask. A refusal, never a
     * fallback: manual {@code run} reaches the local HEAD through its own tier, and an autonomous
     * path has no business branching from whatever this clone happens to hold.
     */
    record NoRemote() implements DefaultBranchDiscovery {}

    /**
     * Origin answered and named no default branch at all — an empty repository whose {@code HEAD}
     * points at an unborn branch. A positive fact, so it is not retried.
     *
     * @param reason one sentence for an operator report
     */
    record Undetermined(String reason) implements DefaultBranchDiscovery {}

    /**
     * Origin never answered — unreachable, cut off on its deadline, or interrupted. The one
     * infrastructure arm: retried under the adapter's bounded git retry and, when it never settles,
     * the failure that releases the claim and opens the remote outage gate.
     *
     * @param reason one sentence for an operator report, credentials already scrubbed
     */
    record Unavailable(String reason) implements DefaultBranchDiscovery {}
}
