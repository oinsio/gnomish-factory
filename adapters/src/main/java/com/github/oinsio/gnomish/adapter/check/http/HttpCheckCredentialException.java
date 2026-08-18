package com.github.oinsio.gnomish.adapter.check.http;

/**
 * Raised when an {@code http} check names an authorization credential the {@code SecretsProvider}
 * cannot resolve (FR11, NFR-S1 of add-plugin-architecture). It names the credential and never its
 * value, and the client turns it into a {@code CannotVerify}: the check is fail-closed — no request
 * is sent unauthenticated and no verdict is invented — while the run reports "cannot verify"
 * instead of burning a stage attempt on a configuration gap.
 *
 * <p>Implements FR11, NFR-S1 of add-plugin-architecture.
 */
final class HttpCheckCredentialException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The name of the credential that would not resolve; never null, unlike {@code getMessage()}. */
    private final String reason;

    HttpCheckCredentialException(String credential) {
        super(message(credential));
        this.reason = message(credential);
    }

    private static String message(String credential) {
        return credential + " is required by this http check's auth, but is missing or blank";
    }

    /**
     * The failure text, non-null by construction — {@link Throwable#getMessage()} is declared
     * nullable, and the poll's {@code CannotVerify} requires a reason it can always print.
     */
    String reason() {
        return reason;
    }
}
