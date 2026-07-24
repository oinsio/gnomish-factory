package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubTrackerAdapterFactory#create} when the GitHub tracker binding cannot be
 * constructed at all: a missing/blank {@code GNOMISH_GITHUB_TOKEN} environment variable (NFR-S1
 * of add-tracker-port) or a malformed {@code tracker.github.repo} value. Mirrors the adapter-local
 * unchecked-exception convention already used for infrastructure/config failures in this package
 * (e.g. {@link GithubHttpException}, {@link GithubLabelProvisioningException}) — the app layer
 * (task 5.15's {@code TakeCommandSupport}/{@code TakeCommand}) lets this propagate uncaught, same
 * as it already does for {@link GithubLabelProvisioningException} at startup.
 *
 * <p>Implements FR17, NFR-S1 of add-tracker-port.
 */
public final class GithubTrackerConfigException extends RuntimeException {

    GithubTrackerConfigException(String message) {
        super(message);
    }
}
