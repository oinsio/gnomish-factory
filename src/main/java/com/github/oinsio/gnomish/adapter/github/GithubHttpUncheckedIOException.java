package com.github.oinsio.gnomish.adapter.github;

import java.io.IOException;

/**
 * Internal unchecked carrier for {@link IOException} across the {@link
 * io.github.resilience4j.retry.Retry#decorateFunction} seam, which requires a
 * {@link java.util.function.Function} (no checked exceptions). {@link
 * GithubRetryConfig} matches on this type's cause, and {@link
 * GithubHttpClient#send} unwraps it into a {@link GithubHttpException} once
 * the retry budget is exhausted.
 *
 * <p>Implements NFR-R2 of add-tracker-port.
 */
final class GithubHttpUncheckedIOException extends RuntimeException {

    GithubHttpUncheckedIOException(IOException cause) {
        super(cause);
    }
}
