package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubClaimLease} when a comments-endpoint call it needs
 * to decide or record the claim (post, list, or best-effort delete) returns
 * a non-2xx response outside the Resilience4j retry budget already applied
 * by {@link com.github.oinsio.gnomish.adapter.github.GithubHttpClient} (FR6, NFR-R1 of add-tracker-port).
 *
 * <p><b>Deliberately NOT a {@code TrackerUnavailableException}</b>, unlike its sibling adapter
 * write exceptions ({@link GithubStateWriteException}, {@link GithubLabelOpsException}, {@link
 * GithubIndexRepairException}). The distinction is <b>not</b> the fault shape — those siblings are
 * raised on the same two shapes this type is, a business non-2xx that already exhausted {@link
 * com.github.oinsio.gnomish.adapter.github.GithubHttpClient}'s transient-retry budget and a 2xx body
 * that would not parse — it is the call site. In this adapter the port marker is what admits a
 * failure into {@code TerminalWriteRetry}'s hold-the-slot loop, and that loop exists only for a
 * <i>terminal</i> write whose outcome is already durable in the task branch: retrying is worth ten
 * minutes because reconcile-on-resume closes the gap if it still fails. A claim is the opposite end
 * of the run — nothing is durable behind it, no reconcile ever completes it, and no slot is held
 * yet. Its failure must abandon the ref at once and let the next feed pass re-offer the task;
 * marking it retryable would park an unclaimed task in a loop nothing can close. For the same
 * reason the claim path is not routed through {@link GithubTransport}: a transport outage here stays
 * the HTTP core's own {@link com.github.oinsio.gnomish.adapter.github.GithubHttpException} instead of
 * being translated into the port's retryable outage, and {@link GithubClaimLease} handles it beside
 * this type — best-effort delete of its own claim comment, then rethrow.
 *
 * <p>Implements FR6 of add-tracker-port.
 */
public final class GithubClaimException extends RuntimeException {

    GithubClaimException(String message) {
        super(message);
    }
}
