package com.github.oinsio.gnomish.adapter.environment;

import com.github.oinsio.gnomish.domain.engine.Finding;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The factory-managed egress guard of one task environment (design D4, FR7):
 * a mitmdump container in non-intercepting SNI/CONNECT mode, joined to the
 * task's internal network and to the default bridge — the box's single route
 * out. The allowlist is rendered from operator config into a factory-private
 * directory the guard mounts read-only ({@link EgressGuardConfig}); DNS is
 * resolved by the guard (the box hands it CONNECT host names and has no port-53
 * route of its own on the internal network).
 *
 * <p>Lifecycle: {@link #ensureRunning()} converges the guard to a running state
 * — create it when missing, start it when stopped, recreate it once when
 * anything else is wrong — and throws {@link GuardUnavailableException} when it
 * cannot (an infrastructure failure, NFR-R1: in-flight checks classify as
 * cannot-verify, no attempt burned). The guard container carries the factory
 * and task labels, so disposal ({@code ContainerEnvironmentDisposal}) and the
 * startup orphan sweep reclaim it exactly like the box, volume, and network
 * (NFR-R2). Denials are read back as structured findings via {@link
 * #denialFindings()} (NFR-O1).
 *
 * <p>Implements FR7, NFR-O1, NFR-R1 of add-sandbox-core.
 */
public final class EgressGuard {

    private static final Logger log = LoggerFactory.getLogger(EgressGuard.class);

    private static final int LOG_TAIL_LINES = 1000;

    private final DockerCli docker;
    private final String key;
    private final String guardImage;
    private final List<String> allowlist;
    private final Path configDir;

    /**
     * @param docker the docker subprocess seam; never null
     * @param key the sanitized environment key naming this task's objects; never blank
     * @param guardImage the operator-configured {@code factory.sandbox.guard-image}; never blank
     * @param allowlist the operator egress allowlist ({@code factory.sandbox.egress-allowlist});
     *     never null, may be empty (default-deny with nothing allowed)
     * @param configDir the factory-private directory the guard config renders into and the guard
     *     container mounts read-only; never inside a working copy or scratch area
     */
    EgressGuard(DockerCli docker, String key, String guardImage, List<String> allowlist, Path configDir) {
        this.docker = docker;
        this.key = key;
        this.guardImage = guardImage;
        this.allowlist = List.copyOf(allowlist);
        this.configDir = configDir;
    }

    /**
     * Converges the guard to a running container: renders the config
     * (idempotent), then creates a missing guard, starts a stopped one, and
     * recreates on anything else — one repair pass, then verification.
     *
     * @throws GuardUnavailableException if the guard is still not running after the repair pass
     * @throws DockerUnavailableException if the runtime itself is unreachable
     */
    public void ensureRunning() {
        EgressGuardConfig.render(configDir, allowlist);
        DockerResult state = docker.run(GuardCommands.inspectGuardRunning(key));
        if (state.ok() && running(state)) {
            return;
        }
        if (state.ok()) {
            log.info("egress guard for {} is stopped; restarting it (NFR-R1)", key);
            docker.run(GuardCommands.startGuard(key));
        } else {
            log.info("egress guard for {} is missing; creating it", key);
            create();
        }
        DockerResult repaired = docker.run(GuardCommands.inspectGuardRunning(key));
        if (repaired.ok() && running(repaired)) {
            return;
        }
        log.warn("egress guard for {} did not come up; recreating it once", key);
        docker.run(GuardCommands.removeGuard(key));
        create();
        DockerResult recreated = docker.run(GuardCommands.inspectGuardRunning(key));
        if (!recreated.ok() || !running(recreated)) {
            throw new GuardUnavailableException("egress guard for " + key + " could not be started: "
                    + recreated.stderr().strip());
        }
    }

    /**
     * The proxy URL the box reaches the guard at — the stable {@code
     * gnomish-guard} network alias, resolvable through the task network's
     * container DNS. The alias, not the per-task container name, is what the
     * reference image bakes into JVM/Gradle proxy configs (task 9.1, D7), so
     * the self-check probes verify exactly the address baked configs will use.
     *
     * @return the guard proxy URL; never null
     */
    public String proxyUrl() {
        return "http://" + GuardCommands.PROXY_ALIAS + ":" + GuardCommands.PROXY_PORT;
    }

    /**
     * The proxy environment fragment for in-box processes — both spellings, as
     * tools disagree on case. Consumed by the layered child-env allowlist as
     * factory-set variables (FR9, task 7.1).
     *
     * @return the proxy variables; never null
     */
    public Map<String, String> proxyEnvironment() {
        String url = proxyUrl();
        return Map.of("HTTP_PROXY", url, "HTTPS_PROXY", url, "http_proxy", url, "https_proxy", url);
    }

    /**
     * The structured denial findings currently in the guard's log tail —
     * metadata only, best-effort: an unreadable log yields no findings (and a
     * warning), never a failure, since denial observability must not take a
     * healthy check down (NFR-O1).
     *
     * @return the parsed denial findings, capped; never null
     */
    public List<Finding> denialFindings() {
        DockerResult logs = docker.run(GuardCommands.guardLogs(key, LOG_TAIL_LINES));
        if (!logs.ok()) {
            log.warn(
                    "could not read egress guard log for {}: {}",
                    key,
                    logs.stderr().strip());
            return List.of();
        }
        return GuardDenialLog.findings(logs.stdout());
    }

    /** The fresh-create path: run the guard on the task network, then give it its bridge leg. */
    private void create() {
        DockerResult run = docker.run(GuardCommands.runGuard(
                key, guardImage, configDir.toAbsolutePath().toString()));
        if (!run.ok()) {
            throw new GuardUnavailableException("docker run of the egress guard for " + key + " failed: "
                    + run.stderr().strip());
        }
        DockerResult bridge = docker.run(GuardCommands.connectBridge(key));
        if (!bridge.ok() && !bridge.stderr().contains("already exists")) {
            throw new GuardUnavailableException("connecting the egress guard for " + key + " to the bridge failed: "
                    + bridge.stderr().strip());
        }
    }

    private static boolean running(DockerResult state) {
        return state.stdout().strip().equals("true");
    }
}
