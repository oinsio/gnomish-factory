package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * A {@code docker} management command that ran and refused: it exited non-zero, or answered a
 * shape the caller cannot act on. Distinct from {@link DockerUnavailableException}, which says the
 * runtime could not be reached at all — that difference decides whether the factory retries the
 * command or classifies the whole operation as an outage.
 *
 * <p>Its own type rather than an {@code IllegalStateException} for one reason (design D5 of
 * type-untrusted-text): the daemon's answer is attacker-influenced — an image author chose the
 * labels, a gnome's own container wrote the state — and a constructor taking a {@code String}
 * leaves the signature as the escape hatch through which that answer reaches a message raw. The
 * parameter is {@link UntrustedText}, so the compiler asks for the carrier and the message
 * composes it through the carrier's own log exit ({@code toString()}).
 *
 * <p>Implements FR1, FR5 of type-untrusted-text; NFR-R1 of add-sandbox-core.
 */
public final class DockerCommandFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param what the command in the caller's vocabulary, e.g. {@code "create network"}; never blank
     * @param subject what the command was for — an environment key, an image reference; never blank
     * @param objectName the docker object an operator would pass to a {@code docker logs} of their
     *     own, or {@code null} where the command names no single object (FR2, UX1 of
     *     polish-sandbox-forensics)
     * @param detail what docker said, as the carrier it was captured in; never null
     */
    public DockerCommandFailedException(
            String what, String subject, @Nullable String objectName, UntrustedText detail) {
        super("docker " + what + " for " + subject + (objectName == null ? "" : " (container " + objectName + ")")
                + " failed: " + detail);
    }

    /**
     * The form for a failure this exception re-states from a lower owner: the message folds the
     * lower one's, which is already inert because it was itself composed from a carrier, and the
     * original stays attached as the cause for the log.
     *
     * @param what the command in the caller's vocabulary; never blank
     * @param subject what the command was for; never blank
     * @param objectName the docker object an operator would name, or {@code null}
     * @param detail what docker said, as the carrier it was captured in; never null
     * @param cause the lower failure this one re-states; never null
     */
    public DockerCommandFailedException(
            String what, String subject, @Nullable String objectName, UntrustedText detail, Throwable cause) {
        this(what, subject, objectName, detail);
        initCause(cause);
    }
}
