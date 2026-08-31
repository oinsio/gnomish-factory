package com.github.oinsio.gnomish.app.port.git;

/**
 * The relationship between a worktree's local branch tip and its {@code origin/<branch>}
 * remote-tracking ref — the replica pair every execution mode reconciles before it resumes.
 *
 * <p>{@link #NO_REMOTE_TRACKING_REF} covers either side being missing: with only one replica there
 * is no pair, and nothing to reconcile.
 *
 * <p>Implements FR9, NFR-R3 of add-git-workflow; FR8 of harden-task-branch-contract.
 */
public enum DivergenceOutcome {

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
