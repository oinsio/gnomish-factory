package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.app.port.run.ContainerRuntimeUnavailableException;
import com.github.oinsio.gnomish.app.port.run.SandboxImageReadiness;
import java.time.Duration;

/**
 * The Docker adapter of {@link SandboxImageReadiness} (design D17 of add-sandbox-base-image): the
 * checks a fresh box runs before its seed clone, run against the configured image without a box.
 * The {@link DockerImageContractInspection} — one {@link ImageFacts} inspection, then {@link
 * ImageContractCheck} on those facts — then {@link BoxGitFloor}: the order {@link
 * ContainerMaterializer#create} meets them in, by the same classes, so the startup assessment adds
 * no spelling of either check. The inspection alone is {@link #inspectContract} (design D1 of
 * add-doctor-command); {@link #assess} is both halves in that order.
 *
 * <p>The floor probe's helper carries the fixed environment key {@link #HELPER_KEY} and the
 * daemon's ownership labels, so the sandbox lifecycle sweep recognizes a helper a killed process
 * left behind like any other helper. A runtime the inspection or the probe could not reach is the
 * typed {@link ContainerRuntimeUnavailableException}, as on materialize; an image the runtime does
 * not hold fails the inspection as it would fail a box ({@link DockerCommandFailedException},
 * unclassified).
 *
 * <p>Implements FR13, NFR-R3 of add-sandbox-base-image; FR1, FR4, NFR-R1 of add-doctor-command.
 */
public final class DockerImageReadiness implements SandboxImageReadiness {

    /** The environment key the floor probe's helper is labelled with: no task owns it. */
    static final String HELPER_KEY = "image-readiness";

    private final DockerImageContractInspection contract;
    private final BoxGitFloor boxGit;

    /**
     * @param commandTimeout the installation's docker command bound ({@code
     *     factory.docker-command-timeout}); never null
     * @param ownership the daemon's ownership mode and project identity, stamped on the probe's
     *     helper; never null
     */
    public DockerImageReadiness(Duration commandTimeout, ObjectOwnership ownership) {
        this(new DockerCli(commandTimeout), ownership, ImageContractCheck.current());
    }

    DockerImageReadiness(DockerCli docker, ObjectOwnership ownership, ImageContractCheck imageContract) {
        this.contract = new DockerImageContractInspection(docker, imageContract);
        this.boxGit = new BoxGitFloor(docker, HELPER_KEY, ownership);
    }

    @Override
    public void inspectContract(String image) {
        contract.inspect(image);
    }

    @Override
    public void inspectPresence(String image) {
        contract.inspectPresence(image);
    }

    @Override
    public void assess(String image) {
        contract.inspect(image);
        try {
            boxGit.require(image);
        } catch (DockerUnavailableException e) {
            throw DockerImageContractInspection.runtimeUnavailable(image, e);
        }
    }
}
