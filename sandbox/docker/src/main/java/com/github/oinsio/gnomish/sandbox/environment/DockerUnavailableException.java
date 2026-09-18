package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ProcessStartException;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * Thrown when the container runtime itself is unavailable — the {@code docker}
 * binary cannot be launched (missing from {@code PATH}, not executable) or the
 * daemon is unreachable ("Cannot connect to the Docker daemon"). Distinct from a
 * docker command that ran and exited non-zero, which {@link DockerCli#run}
 * reports via {@link DockerResult#exitCode()} instead of throwing. One caller raises it for a
 * command that did run: a lifecycle-sweep object listing that exited non-zero, whose empty output
 * cannot be told apart from "no such objects" and therefore must not be acted on (NFR-R1 of
 * add-serve-sandbox-lifecycle).
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

    private static final String DAEMON_UNREACHABLE_MARKER = "cannot connect to the docker daemon";

    /**
     * @param message what could not reach the runtime; never null
     * @param cause the underlying failure (an {@code IOException}, or {@code
     *     null} when the daemon answered but reported itself unreachable)
     */
    public DockerUnavailableException(String message, @Nullable Throwable cause) {
        super(message, cause);
    }

    /**
     * Whether {@code text} is the daemon's own report that it could not be reached — the single
     * detection rule for turning captured docker/git stderr into this exception, shared by every
     * caller that classifies a runtime outage from subprocess output: {@link DockerCli#run} for a
     * docker management command, and the git harvest fetch's {@code ext::} transport classification
     * (which reaches docker only through {@code docker exec}, never through {@link DockerCli}).
     *
     * @param text the captured stderr to check, in whatever case docker or git printed it
     * @return true if {@code text} contains the daemon's own unreachable-daemon wording
     */
    public static boolean reportsDaemonUnreachable(String text) {
        return text.toLowerCase(Locale.ROOT).contains(DAEMON_UNREACHABLE_MARKER);
    }

    /**
     * The form for an outage the daemon itself reported — an unreachable daemon quoting its own
     * refusal, a listing that exited non-zero with a reason. The detail is {@link UntrustedText}
     * rather than a {@code String} so that answer cannot reach the message unrendered (design D5
     * of type-untrusted-text).
     *
     * @param message what could not reach the runtime; never null
     * @param detail what docker said about it; never null
     */
    public DockerUnavailableException(String message, UntrustedText detail) {
        super(message + ": " + detail);
    }
}
