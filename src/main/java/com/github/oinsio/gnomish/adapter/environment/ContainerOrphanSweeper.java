package com.github.oinsio.gnomish.adapter.environment;

import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The startup orphan sweep for container environments (design D2, FR11): finds
 * Docker objects carrying the factory label that belong to no live task and
 * removes them, mirroring worktree pruning. A factory that dies mid-task leaves
 * a labelled container, volume, and network behind; the next instance to start
 * sweeps them, so a crash leaves nothing permanent (NFR-R2).
 *
 * <p>Ownership is decided by name, not by parsing labels back out: the object
 * names of the live keys are computed deterministically ({@link
 * FactoryDockerLabels}), and any listed factory object whose name is not among
 * them is an orphan. Removal is best-effort and by exact name. When the runtime
 * is unavailable — a host-only install with no Docker at all — listing throws
 * {@link DockerUnavailableException}; the sweep then logs and does nothing,
 * never blocking startup.
 *
 * <p>Implements FR11, NFR-R2 of add-sandbox-core.
 *
 * @param docker the docker subprocess seam; never null
 */
record ContainerOrphanSweeper(DockerCli docker) {

    private static final Logger log = LoggerFactory.getLogger(ContainerOrphanSweeper.class);

    /**
     * Removes every factory-labelled container, volume, and network whose name
     * does not belong to one of {@code liveKeys} — the environment keys of tasks
     * this instance currently holds. Passing an empty set removes every factory
     * object, the correct behaviour for a fresh start with no claimed tasks.
     *
     * @param liveKeys the sanitized environment keys to preserve; never null
     */
    void sweep(Set<String> liveKeys) {
        try {
            // A live task's containers are its box AND its egress guard (FR7); both are kept.
            Set<String> liveContainers = liveKeys.stream()
                    .flatMap(key ->
                            Stream.of(FactoryDockerLabels.containerName(key), FactoryDockerLabels.guardName(key)))
                    .collect(Collectors.toUnmodifiableSet());
            removeOrphans(DockerCommands.listContainerNames(), liveContainers, DockerCommands::removeContainer);
            removeOrphans(
                    DockerCommands.listVolumeNames(),
                    names(liveKeys, FactoryDockerLabels::volumeName),
                    DockerCommands::removeVolume);
            removeOrphans(
                    DockerCommands.listNetworkNames(),
                    names(liveKeys, FactoryDockerLabels::networkName),
                    DockerCommands::removeNetwork);
        } catch (DockerUnavailableException e) {
            log.info("container orphan sweep skipped: docker runtime unavailable ({})", e.getMessage());
        }
    }

    private void removeOrphans(List<String> listArgv, Set<String> keep, RemoveCommand remove) {
        for (String name : DockerOutput.lines(docker.run(listArgv).stdout())) {
            if (!keep.contains(name)) {
                log.info("removing orphaned factory object {}", name);
                docker.run(remove.of(name));
            }
        }
    }

    private static Set<String> names(Set<String> keys, Function<String, String> toName) {
        return keys.stream().map(toName).collect(Collectors.toUnmodifiableSet());
    }

    /** The single-name docker remove commands, so one loop serves containers, volumes, and networks. */
    @FunctionalInterface
    private interface RemoveCommand {
        List<String> of(String name);
    }
}
