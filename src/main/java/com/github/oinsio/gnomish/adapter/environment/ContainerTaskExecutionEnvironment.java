package com.github.oinsio.gnomish.adapter.environment;

import com.github.oinsio.gnomish.ResourceLimits;
import com.github.oinsio.gnomish.domain.engine.port.Clock;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The container {@link TaskExecutionEnvironment} adapter (design D2, FR3): one
 * internal-only network, one working-copy volume, and one keep-alive container
 * per task, all created through the {@code docker} CLI as a subprocess (like
 * git, never a socket library). {@code exec} runs inside the container against
 * the volume working copy; the factory↔environment file channel streams through
 * {@code docker exec} ({@link ContainerFileChannel}); {@code dispose} removes all
 * three objects as one idempotent, best-effort teardown. Every created object
 * carries factory labels so the startup orphan sweep can find it (FR11).
 *
 * <p>Git mechanics (design D3, FR3): {@code materialize} seeds the volume with a
 * {@code git clone --no-hardlinks} of the task branch from the factory's local
 * clone, executed by a one-shot {@code run --rm} helper that mounts the factory
 * clone read-only — the task container itself mounts only the volume, so no
 * factory-clone path, remote address, or credential ever exists inside the box.
 * The fast-forward-only fetch behind {@code harvest} arrives with the harvest
 * task (FR5). Runtime outages ({@link DockerUnavailableException}) surface as
 * infrastructure failures, no attempt burned (NFR-R1).
 *
 * <p>Implements FR3, FR4, FR10, FR11, NFR-R1, NFR-R2 of add-sandbox-core.
 */
public final class ContainerTaskExecutionEnvironment implements TaskExecutionEnvironment {

    private static final Logger log = LoggerFactory.getLogger(ContainerTaskExecutionEnvironment.class);

    /**
     * The volume mount point and working directory of every {@code exec} — the git clone's root.
     * Public because the git adapter's {@link ContainerHarvest} realization addresses the in-box
     * repository by this fixed path in its fetch transport (FR5); it is a container-adapter
     * convention, never part of the {@link TaskExecutionEnvironment} contract (D1).
     */
    public static final String WORKING_COPY = "/gnomish/work";

    /** The per-environment scratch root: on the container layer, outside the volume, gone at dispose. */
    static final String SCRATCH = "/gnomish/scratch";

    private final DockerCli docker;
    private final String key;
    private final Path sourceClone;
    private final ContainerHarvest harvester;
    private final String image;
    private final String runtime;
    private final ResourceLimits limits;
    private final boolean enforceDiskQuota;
    private final Clock clock;
    private final ChildEnvAllowlist allowlist;

    private @Nullable ContainerFileChannel channel;
    private @Nullable String branch;

    /**
     * @param docker the docker subprocess seam; never null
     * @param key the sanitized environment key naming this task's objects; never blank
     * @param sourceClone the factory's local clone the working copy is seeded from (design D3,
     *     FR3); mounted read-only into the one-shot seed helper only, never the task container
     * @param harvester the factory-side fetch behind {@link #harvest} (FR5); never null
     * @param image the operator-configured {@code factory.sandbox.image}; required to bind the
     *     container adapter (validated here, per {@code SandboxProperties}); never null or blank
     * @param runtime the {@code --runtime} value (default {@code runc}); never blank
     * @param limits the resource limits applied at container creation (FR10); never null
     * @param enforceDiskQuota whether to add {@code --storage-opt size=} — opt-in, since it needs a
     *     quota-capable storage driver most daemons lack (documented in operator docs)
     * @param clock the start-instant source stamped on each {@link ExecHandle}; never null
     * @param allowlist the layered child-environment allowlist (D6, FR9); the container base is
     *     empty — only composed {@code --env} entries reach an exec child, the image's own {@code
     *     ENV} supplies the runtime environment; never null
     */
    ContainerTaskExecutionEnvironment(
            DockerCli docker,
            String key,
            Path sourceClone,
            ContainerHarvest harvester,
            @Nullable String image,
            String runtime,
            ResourceLimits limits,
            boolean enforceDiskQuota,
            Clock clock,
            ChildEnvAllowlist allowlist) {
        this.docker = docker;
        this.key = key;
        this.sourceClone = sourceClone;
        this.harvester = harvester;
        this.image = requireImage(image);
        this.runtime = runtime;
        this.limits = limits;
        this.enforceDiskQuota = enforceDiskQuota;
        this.clock = clock;
        this.allowlist = allowlist;
    }

    @Override
    public void materialize(String branch, @Nullable String commitPin) {
        log.debug("container environment materializing on branch {} (pin {}) as key {}", branch, commitPin, key);
        String name = FactoryDockerLabels.containerName(key);
        DockerResult inspect = docker.run(DockerCommands.inspectContainerState(name));
        if (inspect.ok()) {
            reattach(name, inspect, branch, commitPin);
        } else {
            create(branch, commitPin);
        }
        channel = new ContainerFileChannel(docker, key, WORKING_COPY, SCRATCH);
        this.branch = branch;
    }

    /**
     * The keep/resume half of FR6: the task container survived (kept after a park, or an
     * interrupted run) — start it if stopped and reuse its volume as-is; a commit pin is still
     * applied through the idempotent seed helper. Nothing is re-cloned: the surviving volume may
     * hold the only copy of unrecorded work.
     */
    private void reattach(String name, DockerResult inspect, String branch, @Nullable String commitPin) {
        log.debug("container environment reattaching to {} for branch {}", name, branch);
        boolean running = inspect.stdout().strip().startsWith("true");
        if (!running) {
            management(DockerCommands.startContainer(name), "start container");
        }
        if (commitPin != null) {
            management(
                    DockerCommands.seedClone(
                            key, image, sourceClone.toAbsolutePath().toString(), branch, commitPin),
                    "pin working copy");
        }
    }

    /** The fresh-materialize path (FR3): network, volume, seed clone, task container, scratch. */
    private void create(String branch, @Nullable String commitPin) {
        // A surviving network (e.g. a container removed by hand, network left behind) is reused;
        // any other network-create failure is real. Volume create is idempotent by docker itself.
        DockerResult network = docker.run(DockerCommands.createNetwork(key));
        if (!network.ok() && !network.stderr().contains("already exists")) {
            throw new IllegalStateException("docker create network for " + key + " failed: "
                    + network.stderr().strip());
        }
        management(DockerCommands.createVolume(key), "create volume");
        // Seed the volume before the task container exists: the clone runs in a one-shot helper
        // that mounts the factory clone read-only, so the task container never sees it (D3, FR3).
        management(
                DockerCommands.seedClone(
                        key, image, sourceClone.toAbsolutePath().toString(), branch, commitPin),
                "seed clone");
        management(
                DockerCommands.runContainer(key, image, runtime, limits, enforceDiskQuota, WORKING_COPY),
                "run container");
        management(
                DockerCommands.exec(key, WORKING_COPY, Map.of(), false, List.of("mkdir", "-p", SCRATCH)),
                "create scratch");
    }

    @Override
    public ExecHandle exec(ExecCommand command) {
        requireMaterialized();
        boolean interactive = command.stdin() != null;
        // Layered allowlist with an empty container base (D6, FR9): only passthrough names read
        // live from the factory environment plus the factory-set fragment become --env entries.
        Map<String, String> env = allowlist.compose(List.of(), command.env());
        List<String> argv = DockerCommands.exec(key, WORKING_COPY, env, interactive, command.command());
        Process process = docker.start(argv, command.mergeStderr());
        Instant startedAt = clock.now();
        ChildProcessStdin.feed(process, command.stdin());
        return new HostExecHandle(process, startedAt);
    }

    @Override
    public void putFile(String path, byte[] content) {
        channel().putFile(path, content);
    }

    @Override
    public Optional<byte[]> readFile(String path, long sizeCap) {
        return channel().readFile(path, sizeCap);
    }

    @Override
    public void harvest() {
        String b = branch;
        if (b == null) {
            throw new IllegalStateException("environment not materialized: key " + key);
        }
        harvester.fetch(FactoryDockerLabels.containerName(key), b);
    }

    @Override
    public void dispose() {
        channel = null;
        branch = null;
        // Best-effort and idempotent, shared with the serve cleaner: an already-gone object, or a
        // runtime that is itself down, must not make teardown throw — the orphan sweep removes
        // whatever a failed dispose left.
        new ContainerEnvironmentDisposal(docker).dispose(key);
    }

    @Override
    public String scratchRoot() {
        requireMaterialized();
        return SCRATCH;
    }

    @Override
    public CapabilityPassport passport() {
        return CapabilityPassport.container();
    }

    private void management(List<String> argv, String what) {
        DockerResult result = docker.run(argv);
        if (!result.ok()) {
            throw new IllegalStateException("docker " + what + " for " + key + " failed: "
                    + result.stderr().strip());
        }
    }

    private ContainerFileChannel channel() {
        ContainerFileChannel c = channel;
        if (c == null) {
            throw new IllegalStateException("environment not materialized: key " + key);
        }
        return c;
    }

    private void requireMaterialized() {
        if (channel == null) {
            throw new IllegalStateException("environment not materialized: key " + key);
        }
    }

    private static String requireImage(@Nullable String image) {
        if (image == null || image.isBlank()) {
            throw new IllegalStateException("factory.sandbox.image must be set to bind the container adapter (FR3)");
        }
        return image;
    }
}
