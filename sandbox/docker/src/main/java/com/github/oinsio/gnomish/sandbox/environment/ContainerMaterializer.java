package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ResourceLimits;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code materialize} half of {@link ContainerTaskExecutionEnvironment} (design D3, FR3 of
 * add-sandbox-core): reattaching to a kept container or creating a fresh one — network, volume,
 * seed clone, task container, scratch. Extracted from {@link ContainerTaskExecutionEnvironment}
 * for file size; the behavior is unchanged.
 */
final class ContainerMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ContainerMaterializer.class);

    private ContainerMaterializer() {}

    /**
     * The keep/resume half of FR6: the task container survived (kept after a park, or an
     * interrupted run) — start it if stopped and reuse its volume as-is; a commit pin is still
     * applied through the idempotent seed helper. Nothing is re-cloned: the surviving volume may
     * hold the only copy of unrecorded work.
     */
    static void reattach(
            DockerCli docker,
            String key,
            String image,
            Path sourceClone,
            String name,
            DockerResult inspect,
            String branch,
            @Nullable String commitPin,
            ObjectOwnership ownership) {
        log.debug("container environment reattaching to {} for branch {}", name, branch);
        boolean running = inspect.stdout().strip().startsWith("true");
        if (!running) {
            management(docker, key, DockerCommands.startContainer(name), "start container");
        }
        if (commitPin != null) {
            management(
                    docker,
                    key,
                    DockerCommands.seedClone(
                            key, image, sourceClone.toAbsolutePath().toString(), branch, commitPin, ownership),
                    "pin working copy");
        }
    }

    /** The fresh-materialize path (FR3): network, volume, seed clone, task container, scratch. */
    static void create(
            DockerCli docker,
            String key,
            String image,
            Path sourceClone,
            String runtime,
            ResourceLimits limits,
            boolean enforceDiskQuota,
            String workingCopy,
            String scratch,
            String branch,
            @Nullable String commitPin,
            ObjectOwnership ownership) {
        // A surviving network (e.g. a container removed by hand, network left behind) is reused;
        // any other network-create failure is real. Volume create is idempotent by docker itself.
        DockerResult network = docker.run(DockerCommands.createNetwork(key, ownership));
        if (!network.ok() && !network.stderr().contains("already exists")) {
            throw new IllegalStateException("docker create network for " + key + " failed: "
                    + network.stderr().strip());
        }
        management(docker, key, DockerCommands.createVolume(key, ownership), "create volume");
        // Seed the volume before the task container exists: the clone runs in a one-shot helper
        // that mounts the factory clone read-only, so the task container never sees it (D3, FR3).
        management(
                docker,
                key,
                DockerCommands.seedClone(
                        key, image, sourceClone.toAbsolutePath().toString(), branch, commitPin, ownership),
                "seed clone");
        management(
                docker,
                key,
                DockerCommands.runContainer(key, image, runtime, limits, enforceDiskQuota, workingCopy, ownership),
                "run container");
        management(
                docker,
                key,
                DockerCommands.exec(key, workingCopy, Map.of(), false, List.of("mkdir", "-p", scratch)),
                "create scratch");
    }

    private static void management(DockerCli docker, String key, List<String> argv, String what) {
        DockerResult result = docker.run(argv);
        if (!result.ok()) {
            throw new IllegalStateException("docker " + what + " for " + key + " failed: "
                    + result.stderr().strip());
        }
    }
}
