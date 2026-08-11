package com.github.oinsio.gnomish.adapter.environment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Renders the egress guard's factory-private configuration (design D4, FR7):
 * the mitmproxy addon script — a constant, never assembled from data — and the
 * operator allowlist as a JSON file the addon reads at startup. Both land in a
 * factory-owned directory mounted read-only into the guard container only; the
 * task container never sees them (NFR-S2).
 *
 * <p>The addon enforces the default-deny allowlist at the CONNECT/plain-HTTP
 * boundary and forwards TLS bytes unmodified ({@code ignore_connection} on
 * every ClientHello) — SNI/CONNECT mode, no TLS opening (NG1: interception is
 * change B). Every denial prints one {@value #DENY_MARKER}-prefixed JSON line
 * of metadata (never bodies) to stdout, where {@code docker logs} makes it
 * readable factory-side (NFR-O1).
 *
 * <p>Allowlist entries are exact host names, IP literals, or {@code *.suffix}
 * wildcards, matched case-insensitively against the destination host (never the
 * port). Entries are validated against a conservative grammar so a config typo
 * cannot smuggle syntax into the rendered JSON.
 *
 * <p>Implements FR7, NFR-O1, NFR-S2 of add-sandbox-core.
 */
final class EgressGuardConfig {

    /** The addon script's file name under the guard's config mount. */
    static final String SCRIPT_FILE = "guard.py";

    /** The allowlist's file name under the guard's config mount. */
    static final String ALLOWLIST_FILE = "allowlist.json";

    /** The stdout prefix marking one structured denial event; kept in sync with {@link GuardDenialLog}. */
    static final String DENY_MARKER = "GNOMISH-EGRESS-DENY ";

    /** Host names, IP literals (v6 included), and {@code *.suffix} wildcards; nothing JSON-active. */
    private static final Pattern ENTRY = Pattern.compile("(\\*\\.)?[A-Za-z0-9._:-]+");

    private static final String SCRIPT = """
            \"\"\"Gnomish egress guard: default-deny allowlist, SNI/CONNECT mode, no TLS opening.\"\"\"

            import json

            from mitmproxy import http

            DENY_MARKER = "GNOMISH-EGRESS-DENY "

            with open("/gnomish-guard/allowlist.json", encoding="utf-8") as f:
                _entries = [e.lower() for e in json.load(f)]
            EXACT = {e for e in _entries if not e.startswith("*.")}
            SUFFIXES = tuple(e[1:] for e in _entries if e.startswith("*."))


            def _allowed(host: str) -> bool:
                h = host.lower()
                return h in EXACT or (SUFFIXES != () and h.endswith(SUFFIXES))


            def _deny(flow, kind: str) -> None:
                event = {"kind": kind, "host": flow.request.host, "port": flow.request.port}
                if kind == "http":
                    event["method"] = flow.request.method
                    event["path"] = flow.request.path
                print(DENY_MARKER + json.dumps(event), flush=True)
                flow.response = http.Response.make(403, b"gnomish egress guard: destination not allowlisted\\n")


            def http_connect(flow):
                if not _allowed(flow.request.host):
                    _deny(flow, "connect")


            def request(flow):
                if flow.response is None and not _allowed(flow.request.host):
                    _deny(flow, "http")


            def tls_clienthello(data):
                data.ignore_connection = True
            """;

    private EgressGuardConfig() {}

    /**
     * Writes the addon script and the rendered allowlist into {@code configDir}
     * (created if absent), overwriting stale content — rendering is idempotent
     * and the directory carries no other state.
     *
     * @param configDir the factory-private directory mounted read-only into the guard
     * @param allowlist the operator allowlist from {@code factory.sandbox.egress-allowlist}
     */
    static void render(Path configDir, List<String> allowlist) {
        try {
            Files.createDirectories(configDir);
            Files.writeString(configDir.resolve(SCRIPT_FILE), SCRIPT, StandardCharsets.UTF_8);
            Files.writeString(configDir.resolve(ALLOWLIST_FILE), allowlistJson(allowlist), StandardCharsets.UTF_8);
            // The guard container's non-root user must read the read-only mount whatever uid it
            // maps to; nothing here is secret (NFR-S1 secrets never enter guard config).
            worldReadable(configDir, "rwxr-xr-x");
            worldReadable(configDir.resolve(SCRIPT_FILE), "rw-r--r--");
            worldReadable(configDir.resolve(ALLOWLIST_FILE), "rw-r--r--");
        } catch (IOException e) {
            throw new UncheckedIOException("could not render egress guard config into " + configDir, e);
        }
    }

    private static void worldReadable(Path path, String permissions) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem: container bind mounts are a POSIX concern; nothing to relax.
        }
    }

    /**
     * The allowlist as the JSON array the addon reads: entries validated,
     * lower-cased, in operator order. The validated grammar contains nothing
     * JSON-active, so plain quoting is exact.
     *
     * @param allowlist the operator allowlist entries
     * @return the rendered JSON array
     * @throws IllegalArgumentException if an entry is blank or outside the host grammar
     */
    static String allowlistJson(List<String> allowlist) {
        return allowlist.stream()
                .map(EgressGuardConfig::validated)
                .map(entry -> '"' + entry + '"')
                .collect(Collectors.joining(",", "[", "]"));
    }

    private static String validated(String entry) {
        String cleaned = entry.strip().toLowerCase(Locale.ROOT);
        if (!ENTRY.matcher(cleaned).matches()) {
            throw new IllegalArgumentException(
                    "factory.sandbox.egress-allowlist entry is not a host, IP, or *.suffix wildcard: '" + entry + "'");
        }
        return cleaned;
    }
}
