package com.github.oinsio.gnomish.adapter.environment;

import com.github.oinsio.gnomish.ResourceLimits;
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

    /** {@code network create --internal} (no route out but the guard) with factory labels. */
    static List<String> createNetwork(String key) {
        return List.of(
                "network",
                "create",
                "--internal",
                "--label",
                FactoryDockerLabels.factoryLabelAssignment(),
                "--label",
                FactoryDockerLabels.taskLabelAssignment(key),
                FactoryDockerLabels.networkName(key));
    }

    /** {@code volume create} for the working copy, with factory labels. */
    static List<String> createVolume(String key) {
        return List.of(
                "volume",
                "create",
                "--label",
                FactoryDockerLabels.factoryLabelAssignment(),
                "--label",
                FactoryDockerLabels.taskLabelAssignment(key),
                FactoryDockerLabels.volumeName(key));
    }

    /**
     * The one-shot seed clone (design D3, FR3): a throwaway {@code run --rm}
     * helper — <em>not</em> the task container — that mounts the factory clone
     * read-only beside the task volume and runs {@code git clone --no-hardlinks}
     * from one into the other, so the task container itself never sees the
     * factory clone, its remote address, or any credential. {@code
     * --no-hardlinks} is mandatory: without it a same-filesystem clone shares
     * object files with the factory repository, letting in-box corruption reach
     * it below git's own mechanics. {@code --single-branch} keeps other tasks'
     * refs out of the box's namespace. The clone gets the agent identity and
     * {@code gc.auto 0} (a one-shot clone needs no background repacking), and
     * {@code origin} is removed — harvest fetches from the environment
     * factory-side, so the box needs no remote at all. {@code --network none}:
     * a local clone needs no network. The optional factory-chosen {@code
     * commitPin} resets the working copy to that commit of the task branch
     * (fresh-box verification, sandboxed judge boxes, {@code --discard-work}).
     *
     * <p>The script is a constant; branch and pin reach it as positional
     * parameters, never interpolated, so neither can alter the script.
     */
    static List<String> seedClone(String key, String image, String sourceClone, String branch, @Nullable String pin) {
        List<String> argv = new ArrayList<>(List.of(
                "run",
                "--rm",
                "--label",
                FactoryDockerLabels.factoryLabelAssignment(),
                "--label",
                FactoryDockerLabels.taskLabelAssignment(key),
                "--network",
                "none",
                "-v",
                sourceClone + ":" + SEED_SOURCE + ":ro",
                "-v",
                FactoryDockerLabels.volumeName(key) + ":" + ContainerTaskExecutionEnvironment.WORKING_COPY,
                image,
                "sh",
                "-c",
                SEED_SCRIPT,
                "gnomish",
                branch));
        if (pin != null) {
            argv.add(pin);
        }
        return List.copyOf(argv);
    }

    /** Where the seed helper sees the factory clone; exists only inside that helper, never the task container. */
    static final String SEED_SOURCE = "/gnomish/src";

    // $1 = task branch, $2 (optional) = factory-chosen commit pin. Paths are constants; set -e
    // makes any failing step fail the helper, surfacing git's stderr through the run result.
    // safe.directory (protected configuration, honored from argv) lets the in-box user read the
    // read-only-mounted factory clone, which carries the host uid, without any config-file write.
    // Idempotent by the .git guard: re-seeding a volume that already holds the clone (resume over
    // a surviving volume, FR6) changes nothing — except an explicit pin, which is always applied.
    private static final String SEED_SCRIPT = """
            set -e
            if [ ! -d %s/.git ]; then
              git -c safe.directory=%s clone --no-hardlinks --single-branch --branch "$1" %s %s
              cd %s
              git remote remove origin
              git config user.name gnome
              git config user.email gnome@sandbox.local
              git config gc.auto 0
            fi
            cd %s
            if [ -n "${2:-}" ]; then git reset --hard "$2"; fi
            """.formatted(
                    ContainerTaskExecutionEnvironment.WORKING_COPY,
                    SEED_SOURCE,
                    SEED_SOURCE,
                    ContainerTaskExecutionEnvironment.WORKING_COPY,
                    ContainerTaskExecutionEnvironment.WORKING_COPY,
                    ContainerTaskExecutionEnvironment.WORKING_COPY);

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
            String workingCopy) {
        List<String> argv = new ArrayList<>(List.of(
                "run",
                "-d",
                "--name",
                FactoryDockerLabels.containerName(key),
                "--label",
                FactoryDockerLabels.factoryLabelAssignment(),
                "--label",
                FactoryDockerLabels.taskLabelAssignment(key),
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
     * {@code inspect} the container's running state and finished-at timestamp, so
     * the aged-environment reaper measures age by runtime metadata — never by
     * file mtimes inside the volume (factory-serve delta). Output is one line:
     * {@code <running> <finishedAt>}, e.g. {@code false 2026-08-07T10:00:00Z}.
     */
    static List<String> inspectContainerState(String name) {
        return List.of("inspect", "-f", "{{.State.Running}} {{.State.FinishedAt}}", name);
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

    /** {@code ps -a} names of every factory container (running or stopped) — the orphan-sweep input. */
    static List<String> listContainerNames() {
        return List.of("ps", "-a", "--filter", FactoryDockerLabels.factoryLabelFilter(), "--format", "{{.Names}}");
    }

    /** {@code volume ls} names of every factory volume. */
    static List<String> listVolumeNames() {
        return List.of("volume", "ls", "--filter", FactoryDockerLabels.factoryLabelFilter(), "--format", "{{.Name}}");
    }

    /** {@code network ls} names of every factory network. */
    static List<String> listNetworkNames() {
        return List.of("network", "ls", "--filter", FactoryDockerLabels.factoryLabelFilter(), "--format", "{{.Name}}");
    }
}
