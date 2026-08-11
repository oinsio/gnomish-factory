package com.github.oinsio.gnomish.adapter.check.github;

/**
 * Thrown by {@link GithubCheckClientFactory#create} when the {@code
 * GNOMISH_GITHUB_ACTIONS_TOKEN} secret does not resolve — construction fails closed at wiring
 * time naming the missing secret, so no stage ever runs with an unauthenticated adapter (FR26 of
 * add-sandbox-core). Mirrors the adapter-local unchecked-exception convention already used for
 * config failures in the sibling tracker package (e.g. {@code GithubTrackerConfigException}).
 *
 * <p>Implements FR8, NFR-S1 of add-external-check-github-actions; FR26 of add-sandbox-core.
 */
public final class GithubCheckTokenException extends RuntimeException {

    GithubCheckTokenException(String message) {
        super(message);
    }
}
