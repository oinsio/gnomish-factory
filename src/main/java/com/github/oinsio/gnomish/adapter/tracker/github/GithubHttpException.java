package com.github.oinsio.gnomish.adapter.tracker.github;

/**
 * Thrown by {@link GithubHttpClient#send(java.net.http.HttpRequest.Builder)}
 * when the Resilience4j retry policy exhausts its attempts without obtaining
 * a non-5xx response — an infrastructure failure that outlasted the retry
 * budget (NFR-R2 of add-tracker-port).
 *
 * <p>Implements NFR-R2 of add-tracker-port.
 */
public final class GithubHttpException extends RuntimeException {

    GithubHttpException(String message, Throwable cause) {
        super(message, cause);
    }
}
