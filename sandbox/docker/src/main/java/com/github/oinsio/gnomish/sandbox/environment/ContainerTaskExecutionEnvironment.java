package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.port.Clock;
import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.ResourceLimits;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
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
    private final ObjectOwnership ownership;

    private @Nullable ContainerFileChannel channel;
    private @Nullable String branch;

    /**
     * @param sourceClone the factory's local clone the working copy is seeded from (design D3,
     *     FR3); mounted read-only into the one-shot seed helper only, never the task container
     * @param harvester the factory-side fetch behind {@link #harvest} (FR5); never null
     * @param image the operator-configured {@code factory.sandbox.image}; required to bind the
     *     container adapter (validated here, per {@code SandboxProperties}); never null or blank
     * @param enforceDiskQuota whether to add {@code --storage-opt size=} — opt-in, since it needs a
     *     quota-capable storage driver most daemons lack (documented in operator docs)
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
            ChildEnvAllowlist allowlist,
            ObjectOwnership ownership) {
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
        this.ownership = ownership;
    }

    @Override
    public void materialize(String branch, @Nullable String commitPin) {
        log.debug("container environment materializing on branch {} (pin {}) as key {}", branch, commitPin, key);
        String name = FactoryDockerLabels.containerName(key);
        DockerResult inspect = docker.run(DockerCommands.inspectContainerState(name));
        if (inspect.ok()) {
            ContainerMaterializer.reattach(
                    docker, key, image, sourceClone, name, inspect, branch, commitPin, ownership);
        } else {
            ContainerMaterializer.create(
                    docker,
                    key,
                    image,
                    sourceClone,
                    runtime,
                    limits,
                    enforceDiskQuota,
                    WORKING_COPY,
                    SCRATCH,
                    branch,
                    commitPin,
                    ownership);
        }
        channel = new ContainerFileChannel(docker, key, WORKING_COPY, SCRATCH);
        this.branch = branch;
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
