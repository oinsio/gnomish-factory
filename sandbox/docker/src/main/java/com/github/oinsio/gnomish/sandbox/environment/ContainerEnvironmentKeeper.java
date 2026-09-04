package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.logtext.LogText;
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
 * <p>Implements FR11 of add-sandbox-core; FR2, NFR-O1, NFR-S1 of polish-sandbox-forensics.
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
     * <p>A successful keep is announced at INFO naming the concrete container (FR2, UX1 of
     * polish-sandbox-forensics): this line, not the caller's, is where the operator reads what
     * survived a park, an abort or a rejected self-check, and it is a name that goes straight
     * into {@code docker logs} / {@code docker cp}. Only the derived object name reaches the
     * line — no environment value, no credential (NFR-S1).
     *
     * @param environmentKey the sanitized environment key of the ended task; never blank
     * @return whether the runtime accepted the stop — {@code false} when the daemon refused it or
     *     was unreachable, so a caller for whom the kept box is evidence (the rejected-self-check
     *     path, FR3 of polish-sandbox-forensics) can say so at its own level. The caller that
     *     merely ends a run ignores it: keeping is best-effort there, as it always was.
     */
    boolean stopKeeping(String environmentKey) {
        String container = FactoryDockerLabels.containerName(environmentKey);
        try {
            DockerResult stopped = docker.run(DockerCommands.stop(container));
            if (!stopped.ok()) {
                // throwable-not-subject: docker answered with a status, not a thrown fault.
                log.debug("best-effort stop of {} was refused: {}", container, LogText.forLog(stopped.stderr()));
                return false;
            }
            log.info("kept container {} stopped; its volume and network are retained for inspection", container);
            return true;
        } catch (RuntimeException e) {
            log.debug("best-effort stop of {} failed", container, e);
            return false;
        }
    }
}
