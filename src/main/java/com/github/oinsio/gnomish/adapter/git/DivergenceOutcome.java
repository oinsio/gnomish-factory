package com.github.oinsio.gnomish.adapter.git;

/**
 * The relationship between a worktree's local branch tip and its {@code origin/<branch>}
 * remote-tracking ref, as classified by {@link WorktreeDivergenceCheck} (FR9, design D9).
 *
 * <p>Implements FR9, NFR-R3 of add-git-workflow.
 */
enum DivergenceOutcome {

    /** No remote-tracking ref exists (no remote, or never fetched) — nothing to reconcile. */
    NO_REMOTE_TRACKING_REF,

    /** Local tip equals the remote-tracking tip. */
    EQUAL,

    /** Local tip is an ancestor of the remote-tracking tip — origin moved ahead. */
    BEHIND,

    /** The remote-tracking tip is an ancestor of the local tip — local has unpushed commits. */
    AHEAD,

    /** Neither tip is an ancestor of the other — the two histories diverged. */
    DIVERGED
}
