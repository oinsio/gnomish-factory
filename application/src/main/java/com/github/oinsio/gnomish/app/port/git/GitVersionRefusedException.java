package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;

/**
 * The startup precondition the git adapter refuses on: the installed git is below the floor the
 * factory's transfers rely on, or it could not report a version at all. The message names the
 * floor, the installed version (or what {@code git --version} printed instead of one) and the one
 * sentence of why, so the operator reads a precondition rather than a crash — the command that
 * ends on it prints the message and nothing else (UX2).
 *
 * <p>Declared beside the other git-adapter refusals of this port package so the command layer's
 * exception reporting can name it without reaching into the adapter; the adapter throws it, the
 * reporter prints it. The unreported-version arm takes git's output as a carrier and renders it
 * through the log exit, like every other exception that quotes a machine's words ({@code
 * TypedExceptionMessageSpec}).
 *
 * <p>Implements FR10, NFR-R2, UX2 of own-git-transfer-argv.
 */
public final class GitVersionRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * A git that reported a version below the floor.
     *
     * @param floor the floor, rendered as dotted numbers
     * @param installed the installed version, rendered the same way
     * @param reason the one sentence of why the floor is where it is
     */
    public GitVersionRefusedException(String floor, String installed, String reason) {
        super(requirement(floor) + "the installed git is " + installed + "; " + reason);
    }

    /**
     * A git that did not report a version: a non-zero exit, or output no version line parses from.
     *
     * @param floor the floor, rendered as dotted numbers
     * @param printed what {@code git --version} printed instead, as captured
     * @param reason the one sentence of why the floor is where it is
     */
    public GitVersionRefusedException(String floor, UntrustedText printed, String reason) {
        super(requirement(floor) + "the installed git did not report a version (git --version printed: "
                + printed.forLog() + "); " + reason);
    }

    private static String requirement(String floor) {
        return "gnomish requires git " + floor + " or newer: ";
    }
}
