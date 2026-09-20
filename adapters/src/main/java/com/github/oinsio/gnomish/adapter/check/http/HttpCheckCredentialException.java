package com.github.oinsio.gnomish.adapter.check.http;

import java.io.Serial;

/**
 * Raised when an {@code http} check names an authorization credential the {@code SecretsProvider}
 * cannot resolve (FR11, NFR-S1 of add-plugin-architecture). The message names the credential and
 * never its value.
 *
 * <p>One of the two {@link HttpCheckRequestException} failures, which states the shared fail-closed
 * outcome: no request is sent unauthenticated, and the run reports "cannot verify" instead of
 * burning a stage attempt on a configuration gap.
 *
 * <p>Implements FR11, NFR-S1 of add-plugin-architecture.
 */
final class HttpCheckCredentialException extends HttpCheckRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    HttpCheckCredentialException(String credential) {
        super("credential '%s' is required by this http check's auth, but is missing or blank".formatted(credential));
    }
}
