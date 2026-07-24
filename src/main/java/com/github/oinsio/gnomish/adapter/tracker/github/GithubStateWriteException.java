package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubStateWrites} and {@link GithubCorrespondence} when
 * a structural-comment POST needed by {@code park}, {@code finish}, {@code
 * recordAbort}, or {@code postNote} returns a non-2xx response outside the
 * Resilience4j retry budget already applied by {@link GithubHttpClient}
 * (FR14, FR18 of add-tracker-port).
 *
 * <p>Implements FR14, FR18 of add-tracker-port.
 */
public final class GithubStateWriteException extends RuntimeException {

    GithubStateWriteException(String message) {
        super(message);
    }
}
