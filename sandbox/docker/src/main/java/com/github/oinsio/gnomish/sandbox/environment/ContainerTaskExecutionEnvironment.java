package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.time.Instant;
import java.time.InstantSource;
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
 * single-branch, tagless clone of the task branch — the transfer owner's {@code SeedPath}
 * value, rendered into the helper's script (design D6 of own-git-transfer-argv) — from the factory's local
 * clone, executed by a one-shot {@code run --rm} helper that mounts the factory
 * clone read-only — the task container itself mounts only the volume, so no
 * factory-clone path, remote address, or credential ever exists inside the box.
 * The fast-forward-only fetch behind {@code harvest} arrives with the harvest
 * task (FR5). Runtime outages ({@link DockerUnavailableException}) surface as
 * infrastructure failures, no attempt burned (NFR-R1).
 *
 * <p>Implements FR3, FR4, FR10, FR11, NFR-R1, NFR-R2 of add-sandbox-core; FR6 of
 * add-parameter-count-gate (the materialize half is an instance held here); FR13 of
 * tighten-spec-contract (the installation's equipment arrives as one {@link BoxEquipment}, the
 * task's inputs beside it).
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
    private final BoxGitLink link;
    private final InstantSource clock;
    private final ChildEnvAllowlist allowlist;
    private final ContainerMaterializer materializer;

    private @Nullable ContainerFileChannel channel;
    private @Nullable String branch;

    /**
     * Takes the installation's {@link BoxEquipment} whole beside the task's own inputs (design D12
     * of tighten-spec-contract). A leaf, yet not one that declares exact members: the members it
     * uses — the docker seam, the container settings, the clock — are the installation half, and
     * declaring them loose beside the link and the allowlist is exactly the mixed parameter list
     * the {@code sandbox/execution-environment} capability rules out.
     *
     * @param equipment the installation's box equipment: the docker seam, the operator-configured
     *     image (required to bind the container adapter, validated as the settings are built),
     *     runtime, limits and disk-quota opt-in, and the exec clock; never null
     * @param key the environment key the box's Docker objects are named by; never blank
     * @param link the factory's local clone the working copy is seeded from (design D3, FR3) and
     *     the factory-side fetch behind {@link #harvest} (FR5); never null
     * @param allowlist the layered child-environment allowlist (D6, FR9); the container base is
     *     empty — only composed {@code --env} entries reach an exec child, the image's own {@code
     *     ENV} supplies the runtime environment; never null
     * @param projectId the project identity stamped, beside the installation's ownership mode, on
     *     every object this environment creates; never blank
     */
    ContainerTaskExecutionEnvironment(
            BoxEquipment equipment, String key, BoxGitLink link, ChildEnvAllowlist allowlist, String projectId) {
        this.docker = equipment.docker();
        this.key = key;
        this.link = link;
        this.clock = equipment.timing().equipment().clock();
        this.allowlist = allowlist;
        this.materializer = new ContainerMaterializer(
                docker, key, link.sourceClone(), equipment.containerSettings(), equipment.ownership(projectId));
    }

    @Override
    public void materialize(String branch, @Nullable String commitPin) {
        log.debug("container environment materializing on branch {} (pin {}) as key {}", branch, commitPin, key);
        String name = FactoryDockerLabels.containerName(key);
        DockerResult inspect = docker.run(DockerCommands.inspectContainerState(name));
        if (inspect.ok()) {
            materializer.reattach(name, inspect, branch, commitPin);
        } else {
            materializer.create(branch, commitPin);
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
        Instant startedAt = clock.instant();
        ChildProcessStdin.feed(process, command.stdin());
        // FR1 of polish-sandbox-forensics: the container handle annotates an exit 137 with the
        // container's own OOM state — advisory only, and only at this one seam every in-box
        // process passes through.
        return new OomAnnotatedExecHandle(
                new HostExecHandle(process, startedAt), docker, FactoryDockerLabels.containerName(key));
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
        link.harvest(FactoryDockerLabels.containerName(key), b);
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
}
