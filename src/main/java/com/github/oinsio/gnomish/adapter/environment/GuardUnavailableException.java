package com.github.oinsio.gnomish.adapter.environment;

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
}
