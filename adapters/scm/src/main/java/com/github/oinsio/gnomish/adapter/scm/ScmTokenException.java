package com.github.oinsio.gnomish.adapter.scm;

/**
 * Thrown by {@link ScmConnection#token} when a built-in's credential does not resolve through the
 * secrets port: the built-in is not constructed, so no check ever runs unauthenticated (NFR-S2 of
 * migrate-verify-to-executor-contract). The message names the secret and the connection, never a
 * value.
 *
 * <p>Implements NFR-S2 of migrate-verify-to-executor-contract.
 */
public final class ScmTokenException extends RuntimeException {

    ScmTokenException(String message) {
        super(message);
    }
}
