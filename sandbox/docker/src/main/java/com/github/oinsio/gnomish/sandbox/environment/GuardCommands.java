package com.github.oinsio.gnomish.sandbox.environment;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Pure builders for the {@code docker} argument vectors of the egress guard
 * (design D4, FR7) — the guard's counterpart to {@link DockerCommands}, split
 * into its own file to honor the file-size invariant. Same discipline: every
 * method returns an immutable argv without the leading {@code docker} binary,
 * values are distinct list elements (never a shell string), and nothing here is
 * assembled from environment-originated content.
 *
 * <p>Implements FR7, FR8, NFR-O1 of add-sandbox-core.
 */
final class GuardCommands {

    /** Where the guard container sees its factory-rendered config, mounted read-only. */
    static final String CONFIG_MOUNT = "/gnomish-guard";

    /** The guard's proxy listen port on the task network. */
    static final int PROXY_PORT = 8080;

    /**
     * The guard's stable DNS alias on the task network. The container's own name
     * is per-task ({@code gnomish-guard-<key>}), but baked image configs — the
     * JVM/Gradle proxy system properties and Maven settings the reference image
     * recipe carries (task 9.1, design D7) — need one address that is identical
     * in every environment; aliases are network-scoped, so concurrent tasks'
     * guards never collide.
     */
    static final String PROXY_ALIAS = "gnomish-guard";

    private GuardCommands() {}

    /**
     * {@code run -d} the guard: mitmdump in non-intercepting SNI/CONNECT mode
     * (the addon script forwards TLS unmodified — no interception, design D4),
     * factory-labelled, joined to the task's internal network, with the
     * factory-rendered config directory (addon script + allowlist) mounted
     * read-only. Config lives outside the box; the task container has no route
     * to the guard's filesystem (NFR-S2). {@code connection_strategy=lazy} keeps
     * the guard from dialing upstream before the addon's allowlist decision.
     */
    static List<String> runGuard(String key, String guardImage, String configDirHostPath) {
        return List.of(
                "run",
                "-d",
                "--name",
                FactoryDockerLabels.guardName(key),
                "--label",
                FactoryDockerLabels.factoryLabelAssignment(),
                "--label",
                FactoryDockerLabels.taskLabelAssignment(key),
                "--network",
                FactoryDockerLabels.networkName(key),
                "--network-alias",
                PROXY_ALIAS,
                "-v",
                configDirHostPath + ":" + CONFIG_MOUNT + ":ro",
                guardImage,
                "mitmdump",
                "--mode",
                "regular",
                "--listen-port",
                Integer.toString(PROXY_PORT),
                "--set",
                "connection_strategy=lazy",
                "-s",
                CONFIG_MOUNT + "/" + EgressGuardConfig.SCRIPT_FILE);
    }

    /**
     * {@code network connect bridge} — the guard's second leg: the task network
     * is {@code --internal}, so the default bridge is the guard's (and therefore
     * the box's) only route out (design D4, FR7).
     */
    static List<String> connectBridge(String key) {
        return List.of("network", "connect", "bridge", FactoryDockerLabels.guardName(key));
    }

    /**
     * {@code logs --tail --timestamps} of the guard container — the bounded read
     * behind denial findings (NFR-O1, NFR-C1): the addon prints one marked JSON
     * line per denial to stdout, and the tail cap bounds what a denial storm can
     * make the factory read.
     *
     * <p>{@code --timestamps} and the optional {@code --since} are the per-round
     * delta (D3 of fix-denial-report-attachment): the guard container outlives
     * the rounds of a lease, so a read from the previous read's daemon-side
     * timestamp is what keeps a round's report free of an earlier round's
     * denials. {@code since} is null on the first read of a guard — the whole log
     * from container start.
     */
    static List<String> guardLogs(String key, int tailLines, @Nullable String since) {
        var argv = new ArrayList<String>(List.of("logs", "--tail", Integer.toString(tailLines), "--timestamps"));
        if (since != null) {
            argv.add("--since");
            argv.add(since);
        }
        argv.add(FactoryDockerLabels.guardName(key));
        return List.copyOf(argv);
    }

    /**
     * {@code inspect} the guard container's runtime id — the identity a durable
     * denial cursor is matched against (FR5 of fix-denial-report-attachment): a
     * position read from one container means nothing in another, and applying it
     * to a foreign log could filter real denials out of the report.
     */
    static List<String> inspectGuardId(String key) {
        return List.of("inspect", "-f", "{{.Id}}", FactoryDockerLabels.guardName(key));
    }

    /** {@code inspect} the guard container's running state — the outage probe (NFR-R1). */
    static List<String> inspectGuardRunning(String key) {
        return List.of("inspect", "-f", "{{.State.Running}}", FactoryDockerLabels.guardName(key));
    }

    /** {@code start} the stopped guard container — the cheap half of the guard restart (NFR-R1). */
    static List<String> startGuard(String key) {
        return List.of("start", FactoryDockerLabels.guardName(key));
    }

    /** {@code rm -f} the guard container — teardown and the recreate half of the restart. */
    static List<String> removeGuard(String key) {
        return List.of("rm", "-f", FactoryDockerLabels.guardName(key));
    }

    /**
     * {@code network inspect} the task network's {@code --internal} flag — the
     * self-check's isolation assertion input (FR8): a network created without
     * the flag is exactly the "silent protection degradation" class D5 names.
     */
    static List<String> inspectNetworkInternal(String key) {
        return List.of("network", "inspect", "-f", "{{.Internal}}", FactoryDockerLabels.networkName(key));
    }

    /** {@code inspect} the task container's runtime in effect, compared against the configured one (FR8). */
    static List<String> inspectRuntime(String key) {
        return List.of("inspect", "-f", "{{.HostConfig.Runtime}}", FactoryDockerLabels.containerName(key));
    }
}
