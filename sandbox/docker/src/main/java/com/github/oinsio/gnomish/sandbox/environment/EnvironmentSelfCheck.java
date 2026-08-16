package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.sandbox.CapabilityPassport;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.IsolationLevel;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
 * <p>Probes, in order: the guard is running (restarted if not, NFR-R1); the
 * in-box process user is non-root (the identity every channel write and the
 * snapshot commit run under, the property design D16 rests on — a root-default
 * image would silently write cage surfaces as root); direct egress bypassing the
 * proxy fails; a non-allowlisted destination via the guard is denied with the
 * guard's 403; an allowlisted destination via the guard passes (skipped when the
 * allowlist names no dialable host — default-deny with nothing allowed has
 * nothing to prove); the task network is {@code --internal} and the container
 * runs under the configured runtime, matching the adapter passport. Any failed
 * probe throws {@link SelfCheckFailedException} naming it
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
    public static final String DENIED_PROBE_HOST = EgressSelfCheckProbes.DENIED_PROBE_HOST;

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSelfCheck.class);

    private final TaskExecutionEnvironment environment;
    private final EgressGuard guard;
    private final DockerCli docker;
    private final String key;
    private final String expectedRuntime;
    private final EgressSelfCheckProbes probes;

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
        this.probes = new EgressSelfCheckProbes(environment, guard, key, List.copyOf(allowlist), sleeper);
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
        probeRunsAsNonRoot();
        probes.probeDirectEgressFails();
        probes.probeDeniedHostRefused();
        probes.probeAllowlistedPasses();
        probeIsolationMatchesPassport();
        log.info("environment self-check passed for {}", key);
    }

    /**
     * The in-box process user must be non-root: channel writes and the snapshot
     * commit inherit this image-default identity, and design D16 depends on it
     * being the unprivileged gnome so symlinks cannot redirect a factory write
     * onto a root-owned cage config. A root {@code id -u} of {@code 0}, or an
     * {@code id} that cannot run at all, fails closed (FR8, D16).
     */
    private void probeRunsAsNonRoot() {
        ExecHandle handle = environment.exec(new ExecCommand(List.of("id", "-u"), Map.of(), null, true));
        String output;
        try (var in = handle.output()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new SelfCheckFailedException("probe-io", "could not read probe output: " + e);
        }
        int exitCode = handle.waitForExit();
        if (exitCode != 0) {
            throw new SelfCheckFailedException(
                    "non-root", "could not read the in-box uid: exit " + exitCode + ", output: " + output);
        }
        if (output.equals("0")) {
            throw new SelfCheckFailedException(
                    "non-root", "the in-box user is root (uid 0); D16 requires a non-root image user");
        }
        log.info("self-check probe non-root passed for {}: in-box uid {}", key, output);
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
}
