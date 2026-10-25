package com.github.oinsio.gnomish.app.port.run;

import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.io.Serial;
import org.jspecify.annotations.Nullable;

/**
 * The configuration error the container adapter refuses a sandbox image on before creating its box:
 * the image's image-contract label is missing — the image is not built on the sandbox base image —
 * or names another contract version than the one the factory was built with (FR5, design D3 of
 * add-sandbox-base-image). The message names the image, the label, the version found and the one
 * required, and ends with the fix — a {@code FROM} line to rebuild from — in two terminal lines, so
 * the operator reads a precondition rather than a box failing later in stranger ways; the command
 * that ends on it prints the message and nothing else.
 *
 * <p>Declared in the sandbox run port so the command layer's exception reporting and the claimed
 * run's failure dispatch can name it without reaching into the container adapter, beside the box's
 * other image precondition ({@code BoxGitVersionRefusedException}). The label value is the image
 * author's text, so it travels as a carrier and renders through the log exit.
 *
 * <p>Implements FR5, NFR-O1, UX2 of add-sandbox-base-image.
 */
public final class ImageContractRefusedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * @param image the sandbox image refused
     * @param found the label's value as the image carries it, or {@code null} when it carries none
     * @param label the name of the image-contract label
     * @param required the contract version the factory was built with
     * @param remedy the fix, ending in the line to rebuild the image from
     */
    public ImageContractRefusedException(
            String image, @Nullable UntrustedText found, String label, int required, String remedy) {
        super(
                found == null
                        ? "the sandbox image \"" + image + "\" is not built on the base (no " + label + "=" + required
                                + " label); " + remedy
                        : "the sandbox image \"" + image + "\" has " + label + "=" + found.forLog()
                                + ", this factory needs " + required + "; " + remedy);
    }
}
