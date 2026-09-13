package com.github.oinsio.gnomish.app;

import java.io.Serial;

/**
 * {@code serve}/{@code take} could not bind the trusted configuration tier at startup because the
 * repository default branch could not be established or refreshed (FR5, FR13 of
 * add-base-ref-resolution): the clone has no {@code origin}, origin named no default branch, the
 * refresh was refused, or origin never answered within the bounded retry.
 *
 * <p>All of these end the process with exit code 1 — "failure outside a claimed run", the same
 * class as a tracker that cannot be provisioned — because no task was claimed and nothing was
 * recorded against one. The refusals (no remote, no default branch, a refused refresh) and the
 * outage (origin unreachable) are distinguished in the message, not in the exit code: the remote
 * outage gate that treats a startup outage differently is a later concern (task 7.x). The message
 * is the operator-facing sentence, printed as is.
 *
 * <p>Implements FR5, FR13 of add-base-ref-resolution.
 */
public final class DefaultBranchUnboundException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param message the operator-facing sentence naming the clone, the step and the cause; never
     *     blank
     */
    public DefaultBranchUnboundException(String message) {
        super(message);
    }
}
