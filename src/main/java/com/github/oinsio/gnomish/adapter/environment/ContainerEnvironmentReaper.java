package com.github.oinsio.gnomish.adapter.environment;

import com.github.oinsio.gnomish.app.serve.TaskEnvironmentDisposal;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-of-life management for container environments (factory-serve delta, FR11,
 * NFR-R2), the container counterpart to the host worktree cleaner: two runtime
 * operations keyed by the sanitized environment key, both measured against the
 * <em>runtime's own metadata</em>, never file mtimes inside a volume.
 *
 * <p>{@link #stopKeeping} realizes the keep semantics for an ended task — it
 * stops the container but leaves the volume and network in place, so a later
 * resume re-materializes cheaply. {@link #reapAged} disposes, through the bound
 * environment port ({@link TaskEnvironmentDisposal}), every stopped factory
 * container whose {@code docker inspect} finished-at is older than the age
 * threshold and which no held task owns; running containers (an active task,
 * possibly another instance's) are always skipped. A task currently occupying a
 * slot of this instance is protected by {@code heldKeys} regardless of age.
 *
 * <p>Scheduling this on the daemon and calling {@link #stopKeeping} at task end
 * is the serve/lifecycle integration's concern; this class is the reusable,
 * daemon-free mechanism. A runtime outage skips the whole pass without throwing.
 *
 * <p>Implements FR11, NFR-R2 of add-sandbox-core.
 */
public final class ContainerEnvironmentReaper {

    private static final Logger log = LoggerFactory.getLogger(ContainerEnvironmentReaper.class);

    private final DockerCli docker;
    private final TaskEnvironmentDisposal disposal;

    /**
     * @param docker the docker subprocess seam; never null
     * @param disposal the container disposal seam aged environments are removed through; never null
     */
    public ContainerEnvironmentReaper(DockerCli docker, TaskEnvironmentDisposal disposal) {
        this.docker = docker;
        this.disposal = disposal;
    }

    /**
     * Stops the container of the ended task {@code environmentKey}, retaining its
     * volume and network (keep semantics). Best-effort: an already-stopped or
     * already-gone container, or a runtime outage, is a no-op, never an error.
     *
     * @param environmentKey the sanitized environment key of the ended task; never blank
     */
    public void stopKeeping(String environmentKey) {
        try {
            docker.run(DockerCommands.stop(FactoryDockerLabels.containerName(environmentKey)));
        } catch (RuntimeException e) {
            log.debug("best-effort stop of {} failed: {}", environmentKey, e.toString());
        }
    }

    /**
     * Disposes every stopped factory container older than {@code ageThreshold}
     * (by runtime finished-at) that no held task owns, through the disposal seam.
     *
     * @param heldKeys environment keys of tasks this instance currently holds; never disposed; never null
     * @param ageThreshold the minimum time since a container finished before it is eligible; never null
     * @param now the current instant the age is measured against; never null
     */
    public void reapAged(Set<String> heldKeys, Duration ageThreshold, Instant now) {
        Instant cutoff = now.minus(ageThreshold);
        try {
            for (String name : DockerOutput.lines(
                    docker.run(DockerCommands.listContainerNames()).stdout())) {
                reapOne(name, heldKeys, cutoff);
            }
        } catch (DockerUnavailableException e) {
            log.info("container reap skipped: docker runtime unavailable ({})", e.getMessage());
        }
    }

    private void reapOne(String name, Set<String> heldKeys, Instant cutoff) {
        Optional<String> key = FactoryDockerLabels.keyFromContainerName(name);
        if (key.isEmpty() || heldKeys.contains(key.get())) {
            return;
        }
        finishedAtIfStopped(name).ifPresent(finishedAt -> {
            if (finishedAt.isBefore(cutoff)) {
                log.info("disposing aged container environment {} (finished at {})", key.get(), finishedAt);
                disposal.dispose(key.get());
            }
        });
    }

    /**
     * The container's finished-at instant when it is stopped, or empty when it is
     * still running or its state cannot be read (removed mid-scan, malformed
     * output) — either way not a reap candidate.
     */
    private Optional<Instant> finishedAtIfStopped(String name) {
        DockerResult result = docker.run(DockerCommands.inspectContainerState(name));
        if (!result.ok()) {
            return Optional.empty();
        }
        String[] parts = result.stdout().strip().split("\\s+");
        if (parts.length != 2 || Boolean.parseBoolean(parts[0])) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(parts[1]));
        } catch (DateTimeParseException e) {
            log.debug("unparseable finished-at for {}: {}", name, parts[1]);
            return Optional.empty();
        }
    }
}
