package com.github.oinsio.gnomish.sandbox.environment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keep semantics for an ended task's container environment (factory-serve delta, FR11): stops
 * the container but leaves the volume and network in place, so a later resume re-materializes
 * cheaply — the sole producer of the **kept environment** of `docs/glossary.md`.
 *
 * <p>Named for keeping, not for reaping: the aged-disposal duty this class once also carried
 * ({@code reapAged}) belongs entirely to {@link SandboxLifecycleSweep} (`sandbox-lifecycle`,
 * task 3.4 of add-serve-sandbox-lifecycle), which is the **environment reaper** the glossary
 * defines. Nothing here disposes of anything.
 *
 * <p>Implements FR11 of add-sandbox-core.
 */
record ContainerEnvironmentKeeper(DockerCli docker) {

    private static final Logger log = LoggerFactory.getLogger(ContainerEnvironmentKeeper.class);

    /**
     * @param docker the docker subprocess seam; never null
     */
    ContainerEnvironmentKeeper {}

    /**
     * Stops the container of the ended task {@code environmentKey}, retaining its
     * volume and network (keep semantics). Best-effort: an already-stopped or
     * already-gone container, or a runtime outage, is a no-op, never an error.
     *
     * @param environmentKey the sanitized environment key of the ended task; never blank
     */
    void stopKeeping(String environmentKey) {
        try {
            docker.run(DockerCommands.stop(FactoryDockerLabels.containerName(environmentKey)));
        } catch (RuntimeException e) {
            log.debug("best-effort stop of {} failed", environmentKey, e);
        }
    }
}
