package com.github.oinsio.gnomish.adapter.environment;

import org.jspecify.annotations.Nullable;

/**
 * Thrown when the container runtime itself is unavailable — the {@code docker}
 * binary cannot be launched (missing from {@code PATH}, not executable) or the
 * daemon is unreachable ("Cannot connect to the Docker daemon"). Distinct from a
 * docker command that ran and exited non-zero, which {@link DockerCli#run}
 * reports via {@link DockerResult#exitCode()} instead of throwing.
 *
 * <p>This is the runtime-outage signal of design D2/NFR-R1: the factory
 * classifies it as an <em>infrastructure</em> failure — no stage attempt is
 * burned, the operation is retried per existing policy, and a persistent outage
 * escalates the task as "cannot execute" — never as a quality failure. It is the
 * container adapter's counterpart to the host adapter's {@link
 * ProcessStartException} for a runtime that is simply not there.
 *
 * <p>Implements NFR-R1 of add-sandbox-core.
 */
public final class DockerUnavailableException extends RuntimeException {

    /**
     * @param message what could not reach the runtime; never null
     * @param cause the underlying failure (an {@code IOException}, or {@code
     *     null} when the daemon answered but reported itself unreachable)
     */
    public DockerUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
