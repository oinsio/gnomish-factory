package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.port.Sleeper;
import com.github.oinsio.gnomish.sandbox.ExecCommand;
import com.github.oinsio.gnomish.sandbox.ExecHandle;
import com.github.oinsio.gnomish.sandbox.TaskExecutionEnvironment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The three egress probes of {@link EnvironmentSelfCheck} (FR7, FR8, design D5 of
 * add-sandbox-core): direct egress fails, a non-allowlisted destination via the guard is denied,
 * an allowlisted one passes. Extracted from {@link EnvironmentSelfCheck} for file size; the
 * behavior is unchanged.
 */
record EgressSelfCheckProbes(
        TaskExecutionEnvironment environment, EgressGuard guard, String key, List<String> allowlist, Sleeper sleeper) {

    /**
     * The non-allowlisted probe destination: an RFC 2606 {@code .invalid} name,
     * denied by the guard's allowlist check before any resolution is attempted.
     */
    static final String DENIED_PROBE_HOST = "selfcheck-denied.gnomish.invalid";

    /** The direct-egress probe target when the allowlist names no dialable host. */
    static final String FALLBACK_PROBE_HOST = "example.com";

    private static final Logger log = LoggerFactory.getLogger(EgressSelfCheckProbes.class);

    /** How many times the first proxied probe retries while the just-(re)started guard is not answering yet. */
    private static final int GUARD_READINESS_ATTEMPTS = 10;

    private static final Duration GUARD_READINESS_PAUSE = Duration.ofMillis(500);

    /** Direct egress bypassing the proxy must fail: the internal network has no route out (FR7, FR8). */
    void probeDirectEgressFails() {
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
    void probeDeniedHostRefused() {
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

    /** An allowlisted destination via the guard must be reachable; vacuous with no dialable entry (FR8). */
    void probeAllowlistedPasses() {
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

    /** The first allowlist entry that is a dialable host — wildcards name no single destination. */
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

    /** One probe's observation; {@code toString} is the diagnostic detail a failure carries. */
    private record Probe(int exitCode, String output) {

        @Override
        public String toString() {
            return "exit " + exitCode + ", output: " + output.strip();
        }
    }
}
