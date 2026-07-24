package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubLabelProvisioner#provision} when the label set for
 * the configured repo cannot be read or created — the "Fork with stale
 * binding dies at startup" scenario of the github-tracker spec (NFR-R4 of
 * add-tracker-port): a misconfigured binding (e.g. a fork pointing at a repo
 * the token cannot write, or cannot even see) must fail fast at startup,
 * before any task is claimed, with an error naming the repo and the likely
 * cause — never surface as a mid-task failure.
 *
 * <p>Implements FR5, NFR-R4 of add-tracker-port.
 */
public final class GithubLabelProvisioningException extends RuntimeException {

    GithubLabelProvisioningException(String message) {
        super(message);
    }
}
