package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final DockerCli docker;
    private final String key;
    private final String guardImage;
    private final List<String> allowlist;
    private final Path configDir;
    private final GuardDenialReads reads;
    private final ObjectOwnership ownership;

    /**
     * @param docker the docker subprocess seam; never null
     * @param key the sanitized environment key naming this task's objects; never blank
     * @param guardImage the operator-configured {@code factory.sandbox.guard-image}; never blank
     * @param allowlist the operator egress allowlist ({@code factory.sandbox.egress-allowlist});
     *     never null, may be empty (default-deny with nothing allowed)
     * @param configDir the factory-private directory the guard config renders into and the guard
     *     container mounts read-only; never inside a working copy or scratch area
     * @param ownership the mode and project identity stamped on the guard container at creation
     *     (FR2 of add-serve-sandbox-lifecycle); never null
     */
    EgressGuard(
            DockerCli docker,
            String key,
            String guardImage,
            List<String> allowlist,
            Path configDir,
            ObjectOwnership ownership) {
        this.docker = docker;
        this.key = key;
        this.guardImage = guardImage;
        this.allowlist = List.copyOf(allowlist);
        this.configDir = configDir;
        this.reads = new GuardDenialReads(docker, key);
        this.ownership = ownership;
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
            repairStep("start", docker.run(GuardCommands.startGuard(key)));
        } else {
            log.info("egress guard for {} is missing; creating it", key);
            create();
        }
        DockerResult repaired = docker.run(GuardCommands.inspectGuardRunning(key));
        if (repaired.ok() && running(repaired)) {
            return;
        }
        log.warn(
                OperatorEvent.EGRESS_GUARD_RECREATED.head() + "egress guard for {} did not come up; recreating it once",
                key);
        repairStep("remove", docker.run(GuardCommands.removeGuard(key)));
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
     * The structured denial findings recorded since the previous call — metadata
     * only, best-effort: an unreadable log — a failed {@code docker logs}, or a
     * daemon that is unreachable altogether — yields no findings (and a
     * warning), never a failure, since denial observability must not take a
     * healthy check or an already-finished round down (NFR-O1, NFR-R1).
     *
     * <p>Consecutive calls return disjoint slices (D3 of
     * fix-denial-report-attachment): the read carries a daemon-side {@code
     * --since} cursor that advances past the last line it saw, so a round asking
     * at its close is told its own denials and never an earlier round's again.
     * A failed read leaves the cursor where it was, so nothing is lost to a
     * transient docker outage. A read that fills its tail window is warned about
     * rather than passed off as complete: the daemon dropped the window's older
     * lines and the cursor moves past them regardless (NFR-O1).
     *
     * <p>Across processes the cursor is durable (FR5): the guard container
     * outlives a lease, so a resumed lease that reattached to a surviving
     * container would otherwise replay every round still in its log. See {@link
     * #restoreDenialCursor} and {@link #denialCursor()}.
     *
     * @return the denial findings recorded since the previous call, capped; never null
     */
    public List<Finding> denialFindings() {
        return reads.findings();
    }

    /**
     * The read position to commit with the attempt these denials belong to,
     * paired with the guard container it was read from (FR5). Empty until a read
     * has advanced the cursor, or when the container's id cannot be read.
     *
     * @return the current denial cursor; never null, possibly empty
     */
    public Optional<DenialCursor> denialCursor() {
        return reads.cursor();
    }

    /**
     * Offers the cursor an earlier lease committed, applied at the first read and
     * only if it names this guard's live container (FR5) — a position stamped by
     * another machine's daemon, or by a container since recreated, is dropped
     * rather than used to filter a log it does not describe.
     *
     * @param cursor the committed cursor; never null
     */
    public void restoreDenialCursor(DenialCursor cursor) {
        reads.restore(cursor);
    }

    /** The fresh-create path: run the guard on the task network, then give it its bridge leg. */
    private void create() {
        // A new container is a new denial source: its id, and any cursor matched against it, differ.
        reads.sourceRecreated();
        DockerResult run = docker.run(GuardCommands.runGuard(
                key, guardImage, configDir.toAbsolutePath().toString(), ownership));
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

    /**
     * One repair sub-step's own outcome. The pass verifies its result afterwards, so a failed
     * sub-step is not by itself a failure — but when the verification then fails, this is the only
     * line saying which step did not take (FR5 of harden-logging-observability).
     */
    private void repairStep(String step, DockerResult result) {
        if (!result.ok()) {
            // throwable-not-subject: docker answered with a status, not a thrown fault.
            log.debug(
                    "egress guard repair step '{}' for {} did not succeed: {}",
                    step,
                    key,
                    LogText.forLog(result.stderr().strip()));
        }
    }

    private static boolean running(DockerResult state) {
        return state.stdout().strip().equals("true");
    }
}
