package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.sandbox.ChildEnvAllowlist;
import com.github.oinsio.gnomish.sandbox.ResourceLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Pure builders for the {@code docker} argument vectors the container adapter
 * runs (design D2): every method returns an immutable argv <em>without</em> the
 * leading {@code docker} binary — {@link DockerCli} prepends that, exactly as
 * {@code GitProcessRunner} owns the {@code git} binary. Keeping argv assembly
 * pure and side-effect-free is what makes the interesting logic — resource-limit
 * flags, factory labels, the volume mount, the runtime knob, the opt-in disk
 * quota — unit-testable to the mutation gate without a Docker daemon; {@link
 * DockerCli} carries only the un-mutatable subprocess plumbing.
 *
 * <p>Object names and label assignments come from {@link FactoryDockerLabels}, so
 * a key's network/volume/container names are identical at create and dispose.
 * Nothing here is ever assembled from environment-originated content: keys are
 * factory-sanitized, argv entries are passed as distinct list elements (never a
 * shell string), so no value can inject an extra flag.
 *
 * <p>Implements FR3, FR4, FR10, FR11 of add-sandbox-core.
 */
final class DockerCommands {

    /**
     * Keep-alive as an argv the image's shell need not provide: {@code sleep}
     * with the max 32-bit second count (~68 years) idles the container between
     * {@code exec} calls, portable across BusyBox and coreutils {@code sleep}
     * (unlike {@code sleep infinity}). The image is replaced as PID 1's command,
     * never relied on for its own entrypoint.
     */
    private static final List<String> KEEP_ALIVE = List.of("sleep", "2147483647");

    private DockerCommands() {}

    /** {@code network create --internal} (no route out but the guard) with the ownership labels. */
    static List<String> createNetwork(String key, ObjectOwnership ownership) {
        List<String> argv = new ArrayList<>(List.of("network", "create", "--internal"));
        argv.addAll(FactoryDockerLabels.ownershipLabelArgs(key, ownership));
        argv.add(FactoryDockerLabels.networkName(key));
        return List.copyOf(argv);
    }

    /** {@code volume create} for the working copy, with the ownership labels. */
    static List<String> createVolume(String key, ObjectOwnership ownership) {
        List<String> argv = new ArrayList<>(List.of("volume", "create"));
        argv.addAll(FactoryDockerLabels.ownershipLabelArgs(key, ownership));
        argv.add(FactoryDockerLabels.volumeName(key));
        return List.copyOf(argv);
    }

    /** The one-shot seed clone (design D3, FR3). Delegated to {@link DockerSeedCloneCommand} for file size. */
    static List<String> seedClone(
            String key,
            String image,
            String sourceClone,
            String branch,
            @Nullable String pin,
            ObjectOwnership ownership) {
        return DockerSeedCloneCommand.seedClone(key, image, sourceClone, branch, pin, ownership);
    }

    /** {@code start} a stopped task container by name — the reattach half of keep semantics (FR6). */
    static List<String> startContainer(String name) {
        return List.of("start", name);
    }

    /**
     * {@code run -d} the keep-alive container: factory-labelled, joined to the
     * internal task network, the working-copy volume mounted at {@code
     * workingCopy} as the working directory, under the configured runtime with
     * CPU/memory/PID limits applied (FR10). The disk quota is added only when
     * {@code enforceDiskQuota} is set: {@code --storage-opt size=} requires a
     * quota-capable storage driver (overlay-on-xfs with {@code pquota}) that
     * ordinary daemons lack, so it stays opt-in rather than failing every
     * container start (operator docs, task 9.5).
     */
    static List<String> runContainer(
            String key,
            String image,
            String runtime,
            ResourceLimits limits,
            boolean enforceDiskQuota,
            String workingCopy,
            ObjectOwnership ownership) {
        List<String> argv = new ArrayList<>(List.of("run", "-d", "--name", FactoryDockerLabels.containerName(key)));
        argv.addAll(FactoryDockerLabels.ownershipLabelArgs(key, ownership));
        argv.addAll(List.of(
                "--network",
                FactoryDockerLabels.networkName(key),
                "--runtime",
                runtime,
                "--cpus",
                limits.cpus(),
                "--memory",
                limits.memory(),
                "--pids-limit",
                Long.toString(limits.pids())));
        if (enforceDiskQuota) {
            argv.add("--storage-opt");
            argv.add("size=" + limits.disk());
        }
        argv.add("-v");
        argv.add(FactoryDockerLabels.volumeName(key) + ":" + workingCopy);
        argv.add("-w");
        argv.add(workingCopy);
        argv.add(image);
        argv.addAll(KEEP_ALIVE);
        return List.copyOf(argv);
    }

    /**
     * {@code exec} of {@code argv} in the task container with {@code workdir} as
     * the working directory and each {@code env} entry passed by {@code -e
     * name=value}; {@code -i} is added when the process is fed stdin. The image's
     * own {@code ENV} supplies the runtime environment (container base is empty,
     * D6) — these {@code -e} entries are the composed allowlist layered on top:
     * operator passthrough plus the factory-set fragment ({@link ChildEnvAllowlist}).
     */
    static List<String> exec(
            String key, String workdir, Map<String, String> env, boolean interactive, List<String> argv) {
        List<String> command = new ArrayList<>();
        command.add("exec");
        command.add("-w");
        command.add(workdir);
        if (interactive) {
            command.add("-i");
        }
        for (Map.Entry<String, String> entry : env.entrySet()) {
            command.add("-e");
            command.add(entry.getKey() + "=" + entry.getValue());
        }
        command.add(FactoryDockerLabels.containerName(key));
        command.addAll(argv);
        return List.copyOf(command);
    }

    /** {@code stop} the container by name — the keep-semantics operation: retains volume and network. */
    static List<String> stop(String name) {
        return List.of("stop", name);
    }

    /**
     * {@code inspect} the container's running state, finished-at timestamp and OOM
     * flag, so the aged-environment reaper measures age by runtime metadata — never
     * by file mtimes inside the volume (factory-serve delta) — and an exit 137 can be
     * told apart from a plain kill. Output is one line: {@code <running> <finishedAt>
     * <oomKilled>}, e.g. {@code false 2026-08-07T10:00:00Z true}.
     *
     * <p>{@code OOMKilled} is appended rather than inserted on purpose: the reattach
     * branch reads the leading field with a {@code startsWith("true")} prefix check,
     * so a field added at the end cannot change how a running container is
     * recognized (FR1, design D1 of polish-sandbox-forensics).
     */
    static List<String> inspectContainerState(String name) {
        return List.of("inspect", "-f", "{{.State.Running}} {{.State.FinishedAt}} {{.State.OOMKilled}}", name);
    }

    /**
     * The {@code OOMKilled} field of an {@link #inspectContainerState} line: {@code true}
     * only when the runtime reported the container's cgroup OOM killer fired (FR1). A short,
     * malformed or absent line reads as {@code false} — the annotation is one-directional, so
     * an unreadable state degrades to no claim rather than a wrong one (NFR-R1).
     *
     * @param stateLine the raw stdout of {@link #inspectContainerState}; never null
     */
    static boolean oomKilled(String stateLine) {
        String[] fields = stateLine.strip().split("\\s+");
        return fields.length >= 3 && "true".equals(fields[2]);
    }

    /** {@code rm -f} the container by name (force removes it even while running). */
    static List<String> removeContainer(String name) {
        return List.of("rm", "-f", name);
    }

    /** {@code volume rm} the volume by name. */
    static List<String> removeVolume(String name) {
        return List.of("volume", "rm", name);
    }

    /** {@code network rm} the network by name. */
    static List<String> removeNetwork(String name) {
        return List.of("network", "rm", name);
    }
}
