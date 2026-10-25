package com.github.oinsio.gnomish.app.port.git;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;

/**
 * The precondition the container adapter refuses a sandbox image on: the image's git is below the
 * floor, or reported no version at all. The box's seed clone runs with the image's own git and
 * reads the factory clone, which is on the reftable ref backend (design D13 of
 * tighten-spec-contract, as amended 2026-10-13) — a git older than 2.45 cannot open it — and relies
 * on the local-clone protections the floor stands for. The message names the image, the floor,
 * what the image's {@code git --version} printed and the remedy, so the operator reads a
 * precondition rather than a failed seed clone; the command that ends on it prints the message and
 * nothing else.
 *
 * <p>Declared beside {@link GitVersionRefusedException}, the host's twin refusal, so the command
 * layer's exception reporting can name it without reaching into the container adapter. Both are
 * judged by the one floor and the one parser of the transfer leaf ({@code GitVersion}); only the
 * subject differs — the factory's git there, the box's here. What the image printed travels as a
 * carrier and renders through the log exit.
 *
 * <p>The remedy names the sandbox base image: its git is built at the floor, so an image built
 * {@code FROM} it passes (FR11 of add-sandbox-base-image).
 *
 * <p>Implements NFR-S2 of tighten-spec-contract (findings F122, F135); FR11 of add-sandbox-base-image.
 */
public final class BoxGitVersionRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param image the sandbox image refused
     * @param floor the floor, rendered as dotted numbers
     * @param printed what {@code git --version} printed in the image (or its error), as captured
     * @param reason the one sentence of why the floor is where it is
     */
    public BoxGitVersionRefusedException(String image, String floor, UntrustedText printed, String reason) {
        super("the sandbox image \"" + image + "\" must carry git " + floor + " or newer (git --version in the"
                + " image printed: " + printed.forLog() + "); the box's seed clone reads the factory clone with"
                + " the image's git: " + reason + ". Rebuild the image FROM the sandbox base image, whose git meets"
                + " the floor (the reference recipe in docs/examples/sandbox-image builds on it), keep no other"
                + " git ahead of it on PATH, and point factory.sandbox.image at the rebuilt image.");
    }
}
