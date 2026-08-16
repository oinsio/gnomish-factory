package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubForeignRepoCheck} when a canonical id's
 * {@code owner/repo} does not resolve — directly or via GitHub's rename
 * redirect — to the configured {@code tracker.github.repo} binding (FR9,
 * design D8). The message names both repos (UX2: every refusal names the
 * reason in the task's own terms).
 *
 * <p>Implements FR9 of add-tracker-port.
 */
public final class GithubForeignRepoException extends RuntimeException {

    GithubForeignRepoException(String message) {
        super(message);
    }

    GithubForeignRepoException(String message, Throwable cause) {
        super(message, cause);
    }
}
