package com.github.oinsio.gnomish.app.port.run;

/**
 * Whether the configured sandbox image meets what every box built from it is checked against
 * (design D17 of add-sandbox-base-image): its contract label and its git floor, judged by the same
 * checks a box runs before its seed clone, without creating a box. The {@code serve} startup
 * assessment asks it once per daemon start, before the first claim, so a stale image is refused
 * where the operator reads the command's exit rather than in a slot's log.
 *
 * <p>A port the application owns, beside {@link ContainerRuntimeProbe}: the checks live in the
 * container runtime's adapter, which this module does not depend on. The composition root binds the
 * Docker adapter; specs script it. An implementation writes nothing but the floor probe's one
 * short-lived helper container, which carries the daemon's ownership labels.
 *
 * <p>Implements FR13, NFR-R3 of add-sandbox-base-image.
 */
@FunctionalInterface
public interface SandboxImageReadiness {

    /**
     * Assesses {@code image}; returns normally when every check passes.
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
