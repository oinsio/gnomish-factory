package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The container realization of {@link TaskEnvironmentDisposal} (factory-serve
 * delta, FR11, NFR-R2): removes a task's container, volume, and network by their
 * deterministic factory names, keyed only by the sanitized environment key — the
 * container counterpart to {@code WorktreeEnvironmentDisposal}, so the serve
 * cleaner disposes container environments through the same seam it disposes host
 * worktrees through, with no live environment handle in hand.
 *
 * <p>Best-effort and idempotent, exactly like the port's own {@code dispose()}
 * (which delegates here): an already-gone object, or a runtime that is itself
 * down, is a no-op rather than an error, since a repeated or racing disposal for
 * the same key is expected. This is the single home of the three-object teardown
 * for both the between-segment {@code dispose()} and the aged-environment sweep.
 *
 * <p>Implements FR11, NFR-R2 of add-sandbox-core.
 */
record ContainerEnvironmentDisposal(DockerCli docker) implements TaskEnvironmentDisposal {

    private static final Logger log = LoggerFactory.getLogger(ContainerEnvironmentDisposal.class);

    /**
     * @param docker the docker subprocess seam; never null
     */
    ContainerEnvironmentDisposal {}

    @Override
    public void dispose(String environmentKey) {
        bestEffort(DockerCommands.removeContainer(FactoryDockerLabels.containerName(environmentKey)));
        // The guard goes before the network: a network with a live endpoint cannot be removed (FR7).
        bestEffort(GuardCommands.removeGuard(environmentKey));
        bestEffort(DockerCommands.removeVolume(FactoryDockerLabels.volumeName(environmentKey)));
        bestEffort(DockerCommands.removeNetwork(FactoryDockerLabels.networkName(environmentKey)));
    }

    private void bestEffort(List<String> argv) {
        try {
            docker.run(argv);
        } catch (RuntimeException e) {
            log.debug("best-effort dispose step failed: {}", e.toString());
        }
    }
}
