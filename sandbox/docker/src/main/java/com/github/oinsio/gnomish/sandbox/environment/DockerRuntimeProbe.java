package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.DoNotMutate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the Docker runtime answers at all — the container-mode prerequisite
 * probe behind the fail-closed D13 refusal ("install Docker or explicitly bind
 * host"), never a silent fallback (G2). Split out of {@link
 * ContainerEnvironments} for file size; the behavior is unchanged.
 *
 * <p>Implements D13, G2 of add-sandbox-core.
 */
public final class DockerRuntimeProbe {

    private static final Logger log = LoggerFactory.getLogger(DockerRuntimeProbe.class);

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
            boolean answered = docker.run(List.of("version", "--format", "{{.Server.Version}}"))
                    .ok();
            if (!answered) {
                // INFO, not WARN: this probe exists to decide a mode, and "no Docker here" is a
                // legitimate answer the caller turns into its own fail-closed refusal (D13, G2).
                // But the refusal names the mode, not the probe, so this is the only line saying
                // the daemon was asked and said no (FR5 of harden-logging-observability).
                // throwable-not-subject: docker answered with a status; nothing was thrown.
                log.info("docker version did not answer ok; container mode is unavailable");
            }
            return answered;
        } catch (DockerUnavailableException e) {
            log.info("the docker runtime is unreachable; container mode is unavailable", e);
            return false;
        }
    }
}
