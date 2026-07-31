package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubHeartbeat} when a comment call it needs to beat the
 * claim (list the thread, or PATCH the resolved claim comment) returns a non-2xx
 * response that is not a {@code 404} (the "claim gone" protocol signal, a {@link
 * com.github.oinsio.gnomish.app.port.tracker.HeartbeatResult.ClaimGone}), or when a
 * beat response body cannot be parsed for its {@code updated_at} version fact. This
 * includes a persistent 5xx: {@link GithubHttpClient} retries it and, once the budget
 * is exhausted, returns it as a non-2xx the beat throws on here. A network/transport
 * failure is different — it surfaces earlier as an infrastructure {@link
 * GithubHttpException}, never this exception.
 *
 * <p>Implements FR1, FR8 of add-claim-heartbeat.
 */
public final class GithubHeartbeatException extends RuntimeException {

    GithubHeartbeatException(String message) {
        super(message);
    }
}
