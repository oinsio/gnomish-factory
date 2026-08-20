package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.List;

/**
 * Whether the Docker runtime answers at all — the container-mode prerequisite
 * probe behind the fail-closed D13 refusal ("install Docker or explicitly bind
 * host"), never a silent fallback (G2). Split out of {@link
 * ContainerEnvironments} for file size; the behavior is unchanged.
 *
 * <p>Implements D13, G2 of add-sandbox-core.
 */
public final class DockerRuntimeProbe {

    private DockerRuntimeProbe() {}

    /**
     * PIT M4 documented exception: {@code @DoNotMutate} — this wrapper only binds
     * the probe to the real {@code docker} binary of the machine the test happens
     * to run on (an integration boundary, the same category as {@code
     * ContainerEnvironments.forTask}'s production wiring): a unit test cannot
     * deterministically assert its boolean against a daemon it does not control.
     * The whole probe decision — ok-exit true, non-zero false, unreachable-runtime
     * false — lives in the package-private overload below and is fully covered by
     * {@code DockerRuntimeProbeSpec}.
     *
     * @return true iff the docker daemon responded
     */
    @DoNotMutate
    public static boolean dockerAvailable() {
        return dockerAvailable(new DockerCli());
    }

    /** The seam-testable probe behind {@link #dockerAvailable()}: true iff {@code docker version} answers ok. */
    static boolean dockerAvailable(DockerCli docker) {
        try {
            return docker.run(List.of("version", "--format", "{{.Server.Version}}"))
                    .ok();
        } catch (DockerUnavailableException e) {
            return false;
        }
    }
}
