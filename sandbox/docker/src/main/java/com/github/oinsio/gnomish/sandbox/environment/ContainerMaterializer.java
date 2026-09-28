package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.untrustedtext.UntrustedParser;
import com.github.oinsio.gnomish.untrustedtext.UntrustedText;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code materialize} half of {@link ContainerTaskExecutionEnvironment} (design D3, FR3 of
 * add-sandbox-core): reattaching to a kept container or creating a fresh one — network, volume,
 * seed clone, task container, scratch. An instance the environment constructs once with the key,
 * the seed source, the container settings and the ownership it materializes under (design D11 of
 * add-parameter-count-gate); {@link #reattach} and {@link #create} take only the per-call values.
 *
 * <p>Both halves end at an INFO anchor naming the environment key, the branch and the image (FR2
 * of harden-logging-observability) — the remote end of the lifecycle a {@code taskId} grep
 * otherwise loses sight of, logged here at its own choke point rather than pulled through the
 * application's anchor vocabulary, which this module cannot see (design D2).
 *
 * <p>Every failure thrown from here names the task container concretely ({@code
 * gnomish-box-<key>}) beside the environment key, so an operator pastes it into {@code docker
 * logs} / {@code docker cp} without deriving it by hand (FR2, UX1 of polish-sandbox-forensics).
 *
 * <p>Implements FR6 of add-parameter-count-gate.
 */
@UntrustedParser
final class ContainerMaterializer {

    private static final Logger log = LoggerFactory.getLogger(ContainerMaterializer.class);

    private final DockerCli docker;
    private final String key;
    private final Path sourceClone;
    private final TaskContainerSettings settings;
    private final ObjectOwnership ownership;

    /**
     * @param docker the docker subprocess seam; never null
     * @param key the sanitized environment key naming this task's objects; never blank
     * @param sourceClone the factory clone the volume is seeded from, mounted read-only into the
     *     one-shot seed helper only; never null
     * @param settings the image, runtime, limits and quota flag the task container runs with
     * @param ownership the mode and project identity stamped on every object created here
     */
    ContainerMaterializer(
            DockerCli docker, String key, Path sourceClone, TaskContainerSettings settings, ObjectOwnership ownership) {
        this.docker = docker;
        this.key = key;
        this.sourceClone = sourceClone;
        this.settings = settings;
        this.ownership = ownership;
    }

    /**
     * The keep/resume half of FR6: the task container survived (kept after a park, or an
     * interrupted run) — start it if stopped and reuse its volume as-is; a commit pin is still
     * applied through the idempotent seed helper. Nothing is re-cloned: the surviving volume may
     * hold the only copy of unrecorded work.
     */
    void reattach(String name, DockerResult inspect, String branch, @Nullable String commitPin) {
        String image = settings.image();
        // FR2 of harden-logging-observability: a container environment's lifecycle is anchored at
        // INFO at its own choke point. Reattaching is the transition an operator most needs to see
        // named — the surviving volume may hold the only copy of unrecorded work, so "reattached"
        // and "created" mean very different things about what the run started from.
        log.info("container environment {} reattached for branch {} (image {})", key, branch, image);
        // @UntrustedParser warrant (design D11): the inspect answer becomes one boolean — is the
        //     surviving container already running — and no text leaves the carrier.
        boolean running = inspect.stdout().forParsing().strip().startsWith("true");
        if (!running) {
            management(DockerCommands.startContainer(name), "start container");
        }
        if (commitPin != null) {
            management(
                    DockerCommands.seedClone(
                            key, image, sourceClone.toAbsolutePath().toString(), branch, commitPin, ownership),
                    "pin working copy");
        }
    }

    /** The fresh-materialize path (FR3): network, volume, seed clone, task container, scratch. */
    void create(String branch, @Nullable String commitPin) {
        String image = settings.image();
        // A surviving network (e.g. a container removed by hand, network left behind) is reused;
        // any other network-create failure is real. Volume create is idempotent by docker itself.
        DockerResult network = docker.run(DockerCommands.createNetwork(key, ownership));
        if (!network.ok() && !network.stderr().contains("already exists")) {
            throw new DockerCommandFailedException(
                    "create network", key, FactoryDockerLabels.containerName(key), network.stderr());
        }
        management(DockerCommands.createVolume(key, ownership), "create volume");
        // Seed the volume before the task container exists: the clone runs in a one-shot helper
        // that mounts the factory clone read-only, so the task container never sees it (D3, FR3).
        management(
                DockerCommands.seedClone(
                        key, image, sourceClone.toAbsolutePath().toString(), branch, commitPin, ownership),
                "seed clone");
        // The image's declared volume paths are read here, at the call site that owns the
        // runtime-outage policy, and never inside the pure argv builder (design D3): a declared
        // path with no explicit mount would otherwise get an anonymous, unlabelled volume the
        // lifecycle model cannot see. The working copy is the one destination this container
        // mounts itself, so an image declaring it keeps the factory's volume.
        DeclaredVolumeOverrides overrides = declaredVolumeOverrides(image);
        management(
                DockerCommands.runContainer(new ContainerRunSpec(
                        key,
                        image,
                        settings.runtime(),
                        settings.limits(),
                        settings.enforceDiskQuota(),
                        ContainerTaskExecutionEnvironment.WORKING_COPY,
                        ownership,
                        overrides)),
                "run container");
        management(
                DockerCommands.exec(
                        key,
                        ContainerTaskExecutionEnvironment.WORKING_COPY,
                        Map.of(),
                        false,
                        List.of("mkdir", "-p", ContainerTaskExecutionEnvironment.SCRATCH)),
                "create scratch");
        // FR2: the creation anchor, logged after the last step rather than before the first — the
        // line states that the environment exists, and a materialize that threw part-way through
        // has not created one. Its failure is already reported by the throw.
        // The override set rides this anchor rather than a line of its own (design D7): one
        // lifecycle event, one anchor, and what the box's declared paths became is part of what
        // "created" means here.
        log.info(
                "container environment {} created for branch {} (image {}); declared volumes made ephemeral: {}",
                key,
                branch,
                image,
                overrides.describe());
    }

    /**
     * The declared-volume override set for the task container, with the owner's refusal re-thrown
     * under this class's own naming convention: an image whose declared volumes cannot be read
     * fails the materialize as an infrastructure failure (NFR-R1, design D4) rather than
     * proceeding with an empty set, which would silently recreate the anonymous-volume leak. The
     * message names the task container an operator would reach for, exactly as {@link
     * #management} does.
     */
    private DeclaredVolumeOverrides declaredVolumeOverrides(String image) {
        try {
            return DeclaredVolumeOverrides.resolve(
                    docker, image, Set.of(ContainerTaskExecutionEnvironment.WORKING_COPY));
        } catch (DockerCommandFailedException e) {
            // The lower message is already inert — it was composed from the carrier docker wrote
            // (design D5) — so it is folded as text and the original stays attached as the cause.
            throw new DockerCommandFailedException(
                    "read declared volumes",
                    key,
                    FactoryDockerLabels.containerName(key),
                    UntrustedText.subprocess(String.valueOf(e.getMessage())),
                    e);
        }
    }

    /**
     * Runs one management command and fails loudly on a non-zero answer, naming the task
     * container the operator would pass to {@code docker logs} / {@code docker cp} — not only
     * the environment key it is derivable from (FR2, UX1 of polish-sandbox-forensics). Only the
     * derived object name and the runtime's own answer reach the message; no environment value
     * or credential does (NFR-S1).
     */
    private void management(List<String> argv, String what) {
        DockerResult result = docker.run(argv);
        if (!result.ok()) {
            throw new DockerCommandFailedException(what, key, FactoryDockerLabels.containerName(key), result.stderr());
        }
    }
}
