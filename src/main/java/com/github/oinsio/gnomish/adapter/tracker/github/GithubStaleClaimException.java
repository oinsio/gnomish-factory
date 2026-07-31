package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubStaleClaimRemoval} when a comment call it needs to
 * remove a stale claim (list the thread for the pre-action version re-check,
 * POST the {@code stale-claim-removed} boundary marker, or DELETE the dead claim
 * comment) returns a non-2xx response that is not a tolerated {@code 404}. This
 * includes a persistent 5xx: {@link GithubHttpClient} retries it and, once the
 * budget is exhausted, returns it as a non-2xx the removal throws on here. A
 * network/transport failure is different — it surfaces earlier as an
 * infrastructure {@link GithubHttpException}, never this exception. A version
 * mismatch is a safe no-op ({@code RemoveStaleClaimResult.Mismatch}), also never
 * this exception.
 *
 * <p>Implements FR4 of add-claim-heartbeat.
 */
public final class GithubStaleClaimException extends RuntimeException {

    GithubStaleClaimException(String message) {
        super(message);
    }
}
