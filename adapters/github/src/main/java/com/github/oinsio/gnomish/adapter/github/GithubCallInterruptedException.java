package com.github.oinsio.gnomish.adapter.github;

/**
 * Thrown by {@link GithubHttpClient#send(java.net.http.HttpRequest.Builder)} when the calling
 * thread is interrupted while a request waits on GitHub: the call was cancelled because its thread
 * was told to stop, not because GitHub could not be reached (FR11 of fix-operator-blockers).
 *
 * <p>The client makes no further attempt and leaves the thread's interrupt set, so the caller
 * reads "this failure is the stop" from its own thread, whichever adapter is bound (the {@code
 * tracker-port} requirement). The type deliberately does not extend {@link GithubHttpException}:
 * every existing handler of an exhausted transport failure — the tracker adapter's translation
 * into a retryable outage, the external-check poll's cannot-verify verdict — would otherwise turn
 * the stop back into an outage, and the retry policy never matches it.
 *
 * <p>Implements FR11, NFR-R3 of fix-operator-blockers.
 */
public final class GithubCallInterruptedException extends RuntimeException {

    GithubCallInterruptedException(String message, InterruptedException cause) {
        super(message, cause);
    }
}
