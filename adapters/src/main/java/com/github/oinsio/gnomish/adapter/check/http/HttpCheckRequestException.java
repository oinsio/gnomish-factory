package com.github.oinsio.gnomish.adapter.check.http;

import java.io.Serial;

/**
 * The common supertype of the two failures that stop an {@code http} check before its request is
 * ever sent: a credential that will not resolve ({@link HttpCheckCredentialException}) and a {@code
 * ${...}} reference this run cannot supply ({@link HttpCheckVariableException}).
 *
 * <p>Both fail closed the same way — {@link HttpExternalCheckClient} turns either into a {@code
 * CannotVerify} carrying {@link #reason()}, so no request leaves unauthenticated or addressing the
 * wrong thing, and the run reports "cannot verify" instead of burning a stage attempt on a
 * configuration gap. Sharing the supertype is what keeps that one classification in one place
 * rather than in a catch clause per subtype.
 *
 * <p>Implements FR11, NFR-S1, NFR-S2 of add-plugin-architecture.
 */
abstract class HttpCheckRequestException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The failure text, non-null unlike {@code getMessage()}; it names what was missing, never a value. */
    private final String reason;

    HttpCheckRequestException(String reason) {
        super(reason);
        this.reason = reason;
    }

    /**
     * The failure text, non-null by construction — {@link Throwable#getMessage()} is declared
     * nullable, and the poll's {@code CannotVerify} requires a reason it can always print.
     */
    final String reason() {
        return reason;
    }
}
