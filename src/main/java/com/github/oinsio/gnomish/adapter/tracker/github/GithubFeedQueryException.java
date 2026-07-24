package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubFeedQuery} and its helpers when the List Issues
 * feed query or the per-issue comments fetch used for abort-fact enrichment
 * fails (FR8 of add-tracker-port).
 *
 * <p>Implements FR8 of add-tracker-port.
 */
public final class GithubFeedQueryException extends RuntimeException {

    GithubFeedQueryException(String message) {
        super(message);
    }

    GithubFeedQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
