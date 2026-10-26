package com.github.oinsio.gnomish.app.port.run;

/**
 * Whether the configured sandbox image meets what every box built from it is checked against
 * (design D17 of add-sandbox-base-image): its contract label and its git floor, judged by the same
 * checks a box runs before its seed clone, without creating a box. The {@code serve} startup
 * assessment asks it once per daemon start, before the first claim, so a stale image is refused
 * where the operator reads the command's exit rather than in a slot's log.
 *
 * <p>Two forms (design D1 of add-doctor-command): {@link #inspectContract} is the contract half
 * alone — one image inspection, no container — and {@link #assess} is both halves in order, the
 * contract and then the box git floor, whose probe is a short-lived helper container. Startup and
 * its rechecks ask {@link #assess}; {@code doctor}, which starts no container, asks only {@link
 * #inspectContract} through the startup owner, and {@link #inspectPresence} of the guard image.
 *
 * <p>A port the application owns, beside {@link ContainerRuntimeProbe}: the checks live in the
 * container runtime's adapter, which this module does not depend on. The composition root binds the
 * Docker adapter; specs script it. An implementation writes nothing but the floor probe's one
 * short-lived helper container, which carries the daemon's ownership labels.
 *
 * <p>Implements FR13, NFR-R3 of add-sandbox-base-image; FR1, FR4, NFR-R1 of add-doctor-command.
 */
public interface SandboxImageReadiness {

    /**
     * Inspects {@code image}'s contract label alone; returns normally when it names the contract
     * version the factory was built with. Starts no container.
     *
     * @param image the configured sandbox image reference; never blank
     * @throws ImageContractRefusedException when the image's contract label is missing or names
     *     another version
     * @throws ContainerRuntimeUnavailableException when the runtime could not be asked at all
     */
    void inspectContract(String image);

    /**
     * Inspects whether the runtime holds {@code image} locally; returns normally when it does. Starts
     * no container and pulls nothing: {@code doctor} asks it of the egress guard image, which a box
     * pulls on first use (FR1, NFR-R1 of add-doctor-command).
     *
     * @param image the image reference; never blank
     * @throws ContainerRuntimeUnavailableException when the runtime could not be asked at all
     * @throws RuntimeException when the runtime does not hold the image or its inspection failed,
     *     the message naming the image and what the runtime answered
     */
    void inspectPresence(String image);

    /**
     * Assesses {@code image}: the contract inspection of {@link #inspectContract}, then the box git
     * floor probe; returns normally when every check passes.
     *
     * @param image the configured sandbox image reference; never blank
     * @throws ImageContractRefusedException when the image's contract label is missing or names
     *     another version
     * @throws com.github.oinsio.gnomish.app.port.git.BoxGitVersionRefusedException when the image's
     *     git is below the factory's floor or reports no version
     * @throws ContainerRuntimeUnavailableException when the runtime could not be asked at all
     */
    void assess(String image);
}
