package com.github.oinsio.gnomish.adapter.check.github;

/**
 * Thrown by {@link GithubCheckToken#requireToken()} when {@code GNOMISH_GITHUB_ACTIONS_TOKEN} is
 * missing or blank. Mirrors the adapter-local unchecked-exception convention already used for
 * config failures in the sibling tracker package (e.g. {@code GithubTrackerConfigException}).
 *
 * <p>Implements FR8, NFR-S1 of add-external-check-github-actions.
 */
public final class GithubCheckTokenException extends RuntimeException {

    GithubCheckTokenException(String message) {
        super(message);
    }
}
