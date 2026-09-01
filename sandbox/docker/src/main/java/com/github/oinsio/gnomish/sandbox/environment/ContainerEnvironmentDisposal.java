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
        bestEffort(
                environmentKey,
                "remove container",
                DockerCommands.removeContainer(FactoryDockerLabels.containerName(environmentKey)));
        // The guard goes before the network: a network with a live endpoint cannot be removed (FR7).
        bestEffort(environmentKey, "remove guard", GuardCommands.removeGuard(environmentKey));
        bestEffort(
                environmentKey,
                "remove volume",
                DockerCommands.removeVolume(FactoryDockerLabels.volumeName(environmentKey)));
        bestEffort(
                environmentKey,
                "remove network",
                DockerCommands.removeNetwork(FactoryDockerLabels.networkName(environmentKey)));
        // FR2 of harden-logging-observability: the disposal anchor, closing the lifecycle the
        // create/reattach anchors opened. Logged unconditionally after the four steps because
        // disposal is best-effort by contract: the environment is gone as far as the factory is
        // concerned whether or not every object went with it, and any step that did not is
        // already named on its own line above.
        log.info("container environment {} disposed", environmentKey);
    }

    /**
     * Runs one teardown step, swallowing its failure per the best-effort contract — but never
     * silently: FR5 of harden-logging-observability requires a degraded result to leave a trace an
     * operator can attribute. The line names both the step and the environment key, because "a
     * dispose step failed" without either is unactionable when a sweep is disposing many
     * environments at once. DEBUG, not WARN: a racing or repeated disposal for the same key is the
     * expected case, and the leftover objects are picked up by the aged-environment sweep.
     */
    private void bestEffort(String environmentKey, String step, List<String> argv) {
        try {
            docker.run(argv);
        } catch (RuntimeException e) {
            log.debug("best-effort dispose step '{}' failed for environment {}", step, environmentKey, e);
        }
    }
}
