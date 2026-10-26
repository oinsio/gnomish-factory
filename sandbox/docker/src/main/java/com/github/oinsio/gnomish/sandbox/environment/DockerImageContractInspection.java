package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeUnavailableException;
import java.time.Duration;

/**
 * The contract half of the image readiness (design D1 of add-doctor-command): one {@link ImageFacts}
 * inspection of the configured image and {@link ImageContractCheck} on those facts — the first check
 * {@link ContainerMaterializer#create} meets, by the same classes — and nothing else. It starts no
 * container and needs no ownership: the box git floor, whose probe is a helper container, is the
 * other half, held beside it by {@link DockerImageReadiness}.
 *
 * <p>A runtime the inspection could not reach is the typed {@link
 * ContainerRuntimeUnavailableException}, as on materialize; an image the runtime does not hold fails
 * the inspection as it would fail a box ({@link DockerCommandFailedException}, unclassified). The
 * one sentence for an unreachable runtime is {@link #runtimeUnavailable}, which the floor's half
 * reuses.
 *
 * <p>Implements FR1, FR4, NFR-R1 of add-doctor-command; FR13, NFR-R3 of add-sandbox-base-image.
 */
public final class DockerImageContractInspection {

    private final DockerCli docker;
    private final ImageContractCheck imageContract;

    /**
     * @param commandTimeout the installation's docker command bound ({@code
     *     factory.docker-command-timeout}); never null
     */
    public DockerImageContractInspection(Duration commandTimeout) {
        this(new DockerCli(commandTimeout), ImageContractCheck.current());
    }

    DockerImageContractInspection(DockerCli docker, ImageContractCheck imageContract) {
        this.docker = docker;
        this.imageContract = imageContract;
    }

    /**
     * Inspects {@code image} once and judges its contract label; returns normally when it holds.
     *
     * @param image the configured sandbox image reference; never blank
     * @throws com.github.oinsio.gnomish.app.port.run.ImageContractRefusedException when the label is
     *     missing or names another version
     * @throws ContainerRuntimeUnavailableException when the runtime could not be asked at all
     */
    public void inspect(String image) {
        try {
            imageContract.require(ImageFacts.read(docker, image));
        } catch (DockerUnavailableException e) {
            throw runtimeUnavailable(image, e);
        }
    }

    /**
     * Inspects {@code image} once, judging nothing but that the runtime holds it: the read every box
     * and guard makes of its image ({@link ImageFacts#read}), without a container and without a
     * pull (FR1, NFR-R1 of add-doctor-command).
     *
     * @param image the image reference; never blank
     * @throws DockerCommandFailedException when the runtime does not hold the image or answered a
     *     shape other than its own
     * @throws ContainerRuntimeUnavailableException when the runtime could not be asked at all
     */
    public void inspectPresence(String image) {
        try {
            ImageFacts.read(docker, image);
        } catch (DockerUnavailableException e) {
            throw runtimeUnavailable(image, e);
        }
    }

    /** The typed refusal for a runtime that could not be asked while assessing {@code image}. */
    static ContainerRuntimeUnavailableException runtimeUnavailable(String image, DockerUnavailableException e) {
        return new ContainerRuntimeUnavailableException(
                "the container runtime is unreachable while assessing image " + image + " (" + e.getMessage()
                        + ") — start Docker, or set DOCKER_HOST",
                e);
    }
}
