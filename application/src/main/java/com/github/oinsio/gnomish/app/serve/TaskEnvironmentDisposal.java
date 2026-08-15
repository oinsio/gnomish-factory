package com.github.oinsio.gnomish.app.serve;

/**
 * The dispose-shaped seam {@link WorktreeJanitor} calls through (design D10, FR14): "dispose of
 * the environment keyed by this sanitized taskId" without the caller knowing what backs it. Today
 * {@code com.github.oinsio.gnomish.adapter.git.WorktreeEnvironmentDisposal} is the only
 * implementation — a host git worktree removed via {@code git worktree remove} — but the seam is
 * shaped so a future sandbox change can swap in a container-plus-volume teardown behind the same
 * {@link #dispose(String)} call, leaving {@link WorktreeJanitor}'s scan-and-age policy untouched.
 *
 * <p>Implements FR14 of add-factory-serve (design D10).
 */
public interface TaskEnvironmentDisposal {

    /**
     * Disposes of the environment keyed by {@code environmentKey} — the sanitized task identifier
     * (see {@code TaskIdSanitizer#sanitize}) a task's environment directory is named after, the
     * only handle {@link WorktreeJanitor} has once it finds an aged directory on disk (it never
     * recovers the original taskId from a directory name). A best-effort operation: disposing an
     * already-gone or never-materialized environment is a no-op, not an error, since a repeated or
     * racing disposal call for the same key is plausible.
     *
     * @param environmentKey the sanitized task identifier naming the environment; never blank
     */
    void dispose(String environmentKey);
}
