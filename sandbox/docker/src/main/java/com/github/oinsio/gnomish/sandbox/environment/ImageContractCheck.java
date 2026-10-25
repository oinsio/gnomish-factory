package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.app.BaseTag;
import com.github.oinsio.gnomish.app.FactoryVersion;
import com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException;
import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The box image's contract check (FR5, design D3 of add-sandbox-base-image): before a box is
 * created, the sandbox image's {@link ImageContract#LABEL} must name the contract version the
 * factory was built with. A missing label means the image is not built on the sandbox base image; a
 * different one means it was built on a base for another factory. Either is refused before any
 * object exists, with an {@link ImageContractRefusedException} whose message ends in the {@code
 * FROM} line to rebuild from. The check logs nothing: the layer that decides on the refusal — the
 * startup assessment, the command, or the daemon condition — writes the one line (design D19).
 *
 * <p>It asks the runtime nothing: the label comes from the {@link ImageFacts} the materialize
 * already read for the declared volumes, so the check adds no inspection and no container start
 * (NFR-R1). Only the sandbox image is checked; the guard image is the factory's own choice.
 *
 * <p>The base tag to rebuild from is the factory's {@link BaseTag}, taken whole from {@link
 * FactoryVersion#baseTag()} (design D11): {@code X.Y} for a final release, the full version for a
 * pre-release — the tag the release that ships the factory pushes, and the one the shipped recipe's
 * {@code GNOMISH_BASE_TAG} default names. The check holds no version and derives nothing. An absent
 * base tag means a development build, which has no published base, so the fix names the base built
 * from the same checkout instead.
 *
 * <p>Built once per installation, held by {@link BoxEquipment}.
 *
 * <p>Implements FR5, NFR-R1, NFR-O1, UX2 of add-sandbox-base-image.
 */
@UntrustedParser
final class ImageContractCheck {

    /** The published sandbox base image, without a tag. */
    static final String BASE_IMAGE = "ghcr.io/oinsio/gnomish-sandbox-base";

    private final int required;
    private final String remedy;

    /**
     * @param contract the image contract the factory was built with; never null
     * @param baseTag the factory's base tag; empty for a development build
     */
    ImageContractCheck(ImageContract contract, Optional<BaseTag> baseTag) {
        this.required = contract.contractVersion();
        this.remedy = remedy(baseTag);
    }

    /** The check of the running factory: its own contract and its own base tag. */
    static ImageContractCheck current() {
        return new ImageContractCheck(
                ImageContract.current(), FactoryVersion.current().baseTag());
    }

    /**
     * Refuses the box image unless its contract label names the required version.
     *
     * @param facts the sandbox image's facts, read once for this box; never null
     * @throws ImageContractRefusedException when the label is missing or names another version
     */
    void require(ImageFacts facts) {
        @Nullable UntrustedText found = facts.labels().get(ImageContract.LABEL);
        // @UntrustedParser warrant (design D11 of type-untrusted-text): the label becomes one
        //     boolean — does it name the required version — and no text leaves the carrier.
        if (found != null && found.forParsing().equals(Integer.toString(required))) {
            return;
        }
        throw new ImageContractRefusedException(facts.image(), found, ImageContract.LABEL, required, remedy);
    }

    /** The fix the refusal ends with: the {@code FROM} line of the base tag, or the local base. */
    static String remedy(Optional<BaseTag> baseTag) {
        return baseTag.map(tag -> "rebuild: FROM " + BASE_IMAGE + ":" + tag.value())
                .orElse("rebuild FROM a base built from this checkout's sandbox-image/Dockerfile"
                        + " (a development build: no published base)");
    }
}
