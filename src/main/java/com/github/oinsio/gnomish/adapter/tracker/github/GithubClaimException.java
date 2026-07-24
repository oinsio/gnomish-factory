package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubClaimLease} when a comments-endpoint call it needs
 * to decide or record the claim (post, list, or best-effort delete) returns
 * a non-2xx response outside the Resilience4j retry budget already applied
 * by {@link GithubHttpClient} (FR6, NFR-R1 of add-tracker-port).
 *
 * <p>Implements FR6 of add-tracker-port.
 */
public final class GithubClaimException extends RuntimeException {

    GithubClaimException(String message) {
        super(message);
    }
}
