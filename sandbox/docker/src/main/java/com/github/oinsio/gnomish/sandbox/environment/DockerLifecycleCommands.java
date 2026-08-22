package com.github.oinsio.gnomish.sandbox.environment;

import java.util.List;

/**
 * The {@code docker} argument vectors the sweep-lifecycle policy reads the host with (FR4, FR8 of
 * add-serve-sandbox-lifecycle) — project-scoped listings, the timing inspects an object's age is
 * measured from, and the existence probe a disposal's outcome is read back with. Split out of
 * {@link DockerCommands}, which owns the create/exec/dispose argv of one task's own environment:
 * these commands answer questions about objects this process did not create and holds no key for,
 * which is a different job on a different population (file-size target, {@code
 * process-invariants.md}).
 *
 * <p>Same contract as {@link DockerCommands}: every method returns an immutable argv WITHOUT the
 * leading {@code docker} binary, which {@link DockerCli} prepends. Object names and label filters
 * come from {@link FactoryDockerLabels}, so a listing selects exactly what a creation stamped.
 *
 * <p>Implements FR4, FR8 of add-serve-sandbox-lifecycle.
 */
final class DockerLifecycleCommands {

    private DockerLifecycleCommands() {}

    /**
     * {@code ps -a} name and raw label string of every {@code projectId}-scoped factory container
     * (the sweep-lifecycle listing input, tab-separated so {@link DockerLabelFormat} can split each
     * line).
     */
    static List<String> listFactoryContainersWithLabels(String projectId) {
        return List.of(
                "ps",
                "-a",
                "--filter",
                FactoryDockerLabels.factoryLabelFilter(),
                "--filter",
                FactoryDockerLabels.projectLabelFilter(projectId),
                "--format",
                "{{.Names}}\t{{.Labels}}");
    }

    /** {@code volume ls} name and raw label string of every {@code projectId}-scoped factory volume. */
    static List<String> listFactoryVolumesWithLabels(String projectId) {
        return List.of(
                "volume",
                "ls",
                "--filter",
                FactoryDockerLabels.factoryLabelFilter(),
                "--filter",
                FactoryDockerLabels.projectLabelFilter(projectId),
                "--format",
                "{{.Name}}\t{{.Labels}}");
    }

    /** {@code network ls} name and raw label string of every {@code projectId}-scoped factory network. */
    static List<String> listFactoryNetworksWithLabels(String projectId) {
        return List.of(
                "network",
                "ls",
                "--filter",
                FactoryDockerLabels.factoryLabelFilter(),
                "--filter",
                FactoryDockerLabels.projectLabelFilter(projectId),
                "--format",
                "{{.Name}}\t{{.Labels}}");
    }

    /**
     * {@code inspect} the four timing fields the sweep-lifecycle evaluator needs in one call:
     * running, finished-at, created-at, started-at — one line, space-separated, e.g. {@code true
     * 0001-01-01T00:00:00Z 2026-08-07T09:00:00Z 2026-08-07T09:00:01Z}. A separate command from
     * {@link DockerCommands#inspectContainerState}, which {@link
     * ContainerTaskExecutionEnvironment}'s self-check keeps using unchanged.
     */
    static List<String> inspectContainerTiming(String name) {
        return List.of(
                "inspect", "-f", "{{.State.Running}} {{.State.FinishedAt}} {{.Created}} {{.State.StartedAt}}", name);
    }

    /** {@code volume inspect} a volume's creation instant (RFC3339) — remnant age has no finished-at. */
    static List<String> inspectVolumeCreatedAt(String name) {
        return List.of("volume", "inspect", "-f", "{{.CreatedAt}}", name);
    }

    /**
     * {@code network inspect} a network's creation instant — remnant age has no finished-at.
     * Unlike a volume's {@code CreatedAt} (already an RFC3339 string), a network's {@code Created}
     * is a Go {@code time.Time}, which {@code {{.Created}}} renders through its default {@code
     * String()} layout ({@code 2026-08-07 09:00:01.1 +0000 UTC}) — unparseable as an {@link
     * java.time.Instant}. {@code json} renders it as the marshalled RFC3339 string instead (with
     * surrounding quotes, which {@link SandboxLifecycleObjectReader} strips).
     */
    static List<String> inspectNetworkCreatedAt(String name) {
        return List.of("network", "inspect", "-f", "{{json .Created}}", name);
    }

    /**
     * {@code inspect} the object's own id — the "does this still exist" probe the sweep-lifecycle
     * evaluator reads a key-triple disposal's outcome back with, since that disposal runs through
     * the best-effort {@code TaskEnvironmentDisposal} port, which reports no exit code. A separate
     * command from the created-at inspects above: the probe asks a different question and must be
     * answerable (and scriptable) on its own.
     *
     * @param kind the object's Docker type; never null
     * @param name the object's own name; never blank
     * @return the argv whose zero exit means "still there"
     */
    static List<String> inspectExists(ObjectKind kind, String name) {
        return switch (kind) {
            case CONTAINER -> List.of("inspect", "-f", "{{.Id}}", name);
            case VOLUME -> List.of("volume", "inspect", "-f", "{{.Name}}", name);
            case NETWORK -> List.of("network", "inspect", "-f", "{{.Id}}", name);
        };
    }
}
