package com.github.oinsio.gnomish.adapter.environment;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The mandatory fail-closed self-check of a materialized sandboxed environment
 * (FR8, design D5), run before the first gnome-product process — round boxes
 * and fresh-box verification/judge boxes alike. The network probes run inside
 * the box via {@code exec()} (they need the box's own viewpoint); the isolation
 * assertion reads the runtime's authoritative metadata ({@code docker inspect})
 * — the "vz silently fell back to QEMU" class is invisible from inside.
 *
 * <p>Probes, in order: the guard is running (restarted if not, NFR-R1); direct
 * egress bypassing the proxy fails; a non-allowlisted destination via the guard
 * is denied with the guard's 403; an allowlisted destination via the guard
 * passes (skipped when the allowlist names no dialable host — default-deny with
 * nothing allowed has nothing to prove); the task network is {@code --internal}
 * and the container runs under the configured runtime, matching the adapter
 * passport. Any failed probe throws {@link SelfCheckFailedException} naming it
 * (UX2) — an infrastructure failure: the environment is rejected and no
 * gnome-product process executes in it. Probe results are logged (NFR-O1).
 *
 * <p>The probes use {@code curl}, which the sandbox image MUST provide — the
 * reference image recipe (task 9.1) bakes it; a missing binary fails the
 * self-check, which is the correct fail-closed reading of an image that cannot
 * be probed.
 *
 * <p>Implements FR8, NFR-O1, NFR-R1, UX2 of add-sandbox-core.
 */
public final class EnvironmentSelfCheck {

    /**
     * The non-allowlisted probe destination: an RFC 2606 {@code .invalid} name,
     * denied by the guard's allowlist check before any resolution is attempted.
     */
    public static final String DENIED_PROBE_HOST = "selfcheck-denied.gnomish.invalid";

    /**
     * The direct-egress probe target when the allowlist names no dialable host.
     */
    static final String FALLBACK_PROBE_HOST = "example.com";

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSelfCheck.class);

    /**
     * How many times the first proxied probe retries while the just-(re)started guard is not answering yet.
     */
    private static final int GUARD_READINESS_ATTEMPTS = 10;

    private static final Duration GUARD_READINESS_PAUSE = Duration.ofMillis(500);

    private final TaskExecutionEnvironment environment;
    private final EgressGuard guard;
    private final DockerCli docker;
    private final String key;
    private final String expectedRuntime;
    private final List<String> allowlist;
    private final Sleeper sleeper;

    /**
     * @param environment     the materialized environment under check; probes run through its exec seam
     * @param guard           this environment's egress guard; never null
     * @param docker          the docker subprocess seam for the isolation metadata reads; never null
     * @param key             the sanitized environment key; never blank
     * @param expectedRuntime the configured {@code factory.sandbox.runtime} the container must run under
     * @param allowlist       the operator egress allowlist; never null, may be empty
     * @param sleeper         the pause seam of the guard-readiness retry; never null
     */
    public EnvironmentSelfCheck(
            TaskExecutionEnvironment environment,
            EgressGuard guard,
            DockerCli docker,
            String key,
            String expectedRuntime,
            List<String> allowlist,
            Sleeper sleeper) {
        this.environment = environment;
        this.guard = guard;
        this.docker = docker;
        this.key = key;
        this.expectedRuntime = expectedRuntime;
        this.allowlist = List.copyOf(allowlist);
        this.sleeper = sleeper;
    }

    /**
     * Runs every probe, in order, failing fast on the first mismatch.
     *
     * @throws SelfCheckFailedException   on the first failed probe, naming it (UX2)
     * @throws GuardUnavailableException  if the guard cannot be brought up (NFR-R1)
     * @throws DockerUnavailableException if the runtime itself is unreachable
     */
    public void verify() {
        guard.ensureRunning();
        probeDirectEgressFails();
        probeDeniedHostRefused();
        probeAllowlistedPasses();
        probeIsolationMatchesPassport();
        log.info("environment self-check passed for {}", key);
    }

    /**
     * Direct egress bypassing the proxy must fail: the internal network has no route out (FR7, FR8).
     */
    private void probeDirectEgressFails() {
        String target = dialableAllowlistHost().orElse(FALLBACK_PROBE_HOST);
        Probe probe = run("curl", "--noproxy", "*", "-sS", "-o", "/dev/null", "--max-time", "5", url(target));
        if (probe.exitCode() == 0) {
            throw new SelfCheckFailedException(
                    "direct-egress", "direct connection to " + target + " unexpectedly succeeded");
        }
        log.info("self-check probe direct-egress passed for {}: no direct route", key);
    }

    /**
     * A non-allowlisted destination via the guard must be denied with the guard's
     * own 403 (FR7, FR8). This is the first proxied probe after {@code
     * ensureRunning}, so a not-yet-listening just-(re)started mitmdump (a failed
     * connection, never a wrong status) is retried briefly before it counts as a
     * failure; a wrong HTTP status is a real allowlist-enforcement failure and
     * fails immediately.
     */
    private void probeDeniedHostRefused() {
        Probe probe = null;
        for (int attempt = 0; attempt < GUARD_READINESS_ATTEMPTS; attempt++) {
            if (attempt > 0) {
                sleeper.sleep(GUARD_READINESS_PAUSE);
            }
            probe = run(
                    "curl",
                    "-sS",
                    "-o",
                    "/dev/null",
                    "--max-time",
                    "10",
                    "-w",
                    "%{http_code}",
                    "-x",
                    guard.proxyUrl(),
                    url(DENIED_PROBE_HOST));
            if (probe.output().strip().endsWith("403")) {
                log.info("self-check probe denied-host passed for {}: guard denies non-allowlisted", key);
                return;
            }
            if (probe.exitCode() == 0) {
                break;
            }
            log.debug("self-check probe denied-host retrying for {}: guard not answering yet ({})", key, probe);
        }
        throw new SelfCheckFailedException(
                "denied-host", "expected the guard's 403 for " + DENIED_PROBE_HOST + ", observed: " + probe);
    }

    /**
     * An allowlisted destination via the guard must be reachable; vacuous with no dialable entry (FR8).
     */
    private void probeAllowlistedPasses() {
        Optional<String> target = dialableAllowlistHost();
        if (target.isEmpty()) {
            log.info("self-check probe allowlisted-host skipped for {}: allowlist names no dialable host", key);
            return;
        }
        Probe probe =
                run("curl", "-sS", "-o", "/dev/null", "--max-time", "15", "-x", guard.proxyUrl(), url(target.get()));
        if (probe.exitCode() != 0) {
            throw new SelfCheckFailedException(
                    "allowlisted-host", "allowlisted " + target.get() + " unreachable via guard: " + probe);
        }
        log.info("self-check probe allowlisted-host passed for {}: {} reachable via guard", key, target.get());
    }

    /**
     * The isolation in effect must match the passport: internal network, configured runtime (FR8, D5).
     */
    private void probeIsolationMatchesPassport() {
        CapabilityPassport passport = environment.passport();
        if (passport.isolation() != IsolationLevel.CONTAINER || !passport.egressControlled()) {
            throw new SelfCheckFailedException(
                    "isolation", "passport does not declare guarded container isolation: " + passport);
        }
        String internal =
                docker.run(GuardCommands.inspectNetworkInternal(key)).stdout().strip();
        if (!internal.equals("true")) {
            throw new SelfCheckFailedException(
                    "isolation", "task network is not internal-only (Internal=" + internal + ")");
        }
        String runtime = docker.run(GuardCommands.inspectRuntime(key)).stdout().strip();
        if (!runtime.equals(expectedRuntime)) {
            throw new SelfCheckFailedException(
                    "isolation", "container runtime is '" + runtime + "', expected '" + expectedRuntime + "'");
        }
        log.info("self-check probe isolation passed for {}: internal network, runtime {}", key, runtime);
    }

    /**
     * The first allowlist entry that is a dialable host — wildcards name no single destination.
     */
    private Optional<String> dialableAllowlistHost() {
        return allowlist.stream().filter(entry -> !entry.contains("*")).findFirst();
    }

    private static String url(String host) {
        return "http://" + host + "/";
    }

    private Probe run(String... argv) {
        ExecHandle handle = environment.exec(new ExecCommand(List.of(argv), Map.of(), null, true));
        String output;
        try (var in = handle.output()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SelfCheckFailedException("probe-io", "could not read probe output: " + e);
        }
        return new Probe(handle.waitForExit(), output);
    }

    /**
     * One probe's observation; {@code toString} is the diagnostic detail a failure carries.
     */
    private record Probe(int exitCode, String output) {

        @Override
        public String toString() {
            return "exit " + exitCode + ", output: " + output.strip();
        }
    }
}
