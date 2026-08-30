package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubClaimLease} when a comments-endpoint call it needs
 * to decide or record the claim (post, list, or best-effort delete) returns
 * a non-2xx response outside the Resilience4j retry budget already applied
 * by {@link com.github.oinsio.gnomish.adapter.github.GithubHttpClient} (FR6, NFR-R1 of add-tracker-port).
 *
 * <p><b>Deliberately NOT a {@code TrackerUnavailableException}</b>, unlike its sibling adapter
 * write exceptions ({@link GithubStateWriteException}, {@link GithubLabelOpsException}, {@link
 * GithubIndexRepairException}) and unlike {@link GithubTransportException}. That port marker means
 * "the tracker could not be reached, so a later retry may succeed", and {@code TerminalWriteRetry}
 * loops on exactly it. This type carries the opposite fact: the tracker ANSWERED. It is raised for
 * a business non-2xx that already exhausted {@link
 * com.github.oinsio.gnomish.adapter.github.GithubHttpClient}'s transient-retry budget, and for a
 * 2xx body that would not parse — faults that must surface immediately, since retrying them only
 * repeats the same answer. A genuine outage on these same call sites arrives as {@link
 * GithubTransportException} and is classified as retryable there.
 *
 * <p>Implements FR6 of add-tracker-port.
 */
public final class GithubClaimException extends RuntimeException {

    GithubClaimException(String message) {
        super(message);
    }
}
