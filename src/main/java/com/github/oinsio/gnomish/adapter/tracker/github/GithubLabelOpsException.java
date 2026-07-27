package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubLabelOps} when a label mutation call returns an
 * error response that is not the tolerated "already absent" 404 on remove
 * (FR5 of add-tracker-port).
 *
 * <p>Implements FR5 of add-tracker-port.
 */
public final class GithubLabelOpsException extends RuntimeException {

    GithubLabelOpsException(String message) {
        super(message);
    }
}
