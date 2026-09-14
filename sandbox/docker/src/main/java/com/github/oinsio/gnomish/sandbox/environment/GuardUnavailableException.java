package com.github.oinsio.gnomish.sandbox.environment;

/**
 * The egress guard is down and the factory could not bring it back (NFR-R1):
 * thrown after the restart path — start the stopped container, or recreate it —
 * has been attempted and failed. Classified exactly like {@link
 * DockerUnavailableException}: an infrastructure failure — in-flight checks
 * classify as cannot-verify, no stage attempt is burned, and a persistent
 * outage escalates as cannot-execute; never a quality failure.
 *
 * <p>Implements NFR-R1 of add-sandbox-core.
 */
public final class GuardUnavailableException extends RuntimeException {

    /**
     * @param message what the guard could not do; never null
     */
    public GuardUnavailableException(String message) {
        super(message);
    }

    /**
     * The form for a failure this class re-throws from a lower owner — the declared-volume
     * resolver's refusal (NFR-R1 of fix-image-declared-volumes), whose own message is folded into
     * {@code message} while the original stays attached as the cause for the log.
     *
     * @param message what the guard could not do; never null
     * @param cause the lower failure this one re-states; never null
     */
    public GuardUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
