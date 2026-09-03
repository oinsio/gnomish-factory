package com.github.oinsio.gnomish.sandbox.environment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.logtext.OperatorEvent;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses the guard's stdout into structured denial findings (NFR-O1, UX3): the
 * addon prints one {@value EgressGuardConfig#DENY_MARKER}-prefixed JSON line per
 * denied destination — metadata only (host, port, and for plain HTTP the method
 * and path), never request bodies — and this parser turns exactly those lines
 * into {@link Finding}s for the task report, so a blocked attempt is a visible
 * signal an operator can tell apart from an outage at a glance.
 *
 * <p>Guard output is environment-adjacent data and is treated as inert
 * (NFR-S3): unmarked lines are skipped, a malformed marked line is dropped (never a failure —
 * losing one event must not fail a check), string fields are length-capped, the path is cut at
 * its query string, and the number of parsed events is capped (NFR-C1); the findings funnel
 * (task 8.1) applies the publication-side sanitization on top.
 *
 * <p>Dropped lines are counted, not narrated (D4 of harden-logging-observability): a guard whose
 * output shape the factory does not understand produces one malformed line per denied request,
 * and one WARN each would bury the round's real findings. The parse of one read is one operation,
 * so it aggregates locally and emits a single line naming the environment key and what it lost —
 * the aggregate-per-call invariant, deliberately not the cross-call {@code RepeatSuppressor}.
 *
 * <p>Implements NFR-O1, NFR-C1, NFR-S3, UX3 of add-sandbox-core; NFR-S1 of
 * fix-denial-report-attachment; FR5, FR12 of harden-logging-observability.
 */
final class GuardDenialLog {

    /** The most denial events one read turns into findings; a storm beyond this is truncated with a warning. */
    static final int MAX_EVENTS = 200;

    private static final int MAX_FIELD_LENGTH = 300;

    private static final Logger log = LoggerFactory.getLogger(GuardDenialLog.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private GuardDenialLog() {}

    /**
     * The denial findings in {@code guardStdout}, in log order, capped at
     * {@link #MAX_EVENTS}.
     *
     * @param key the environment key whose guard produced this output, so an aggregate warning
     *     names the box it concerns; never blank
     * @param guardStdout the raw guard container log output; never null
     * @return one finding per parseable denial event; never null
     */
    static List<Finding> findings(String key, String guardStdout) {
        List<Finding> findings = new ArrayList<>();
        var drops = new Drops();
        for (String line : guardStdout.split("\n")) {
            // The marker is matched anywhere in the line: mitmproxy forwards addon print output
            // through its own event log, which may prepend a timestamp/level prefix.
            int marker = line.indexOf(EgressGuardConfig.DENY_MARKER);
            if (marker < 0) {
                continue;
            }
            if (findings.size() == MAX_EVENTS) {
                log.warn(
                        OperatorEvent.GUARD_DENIAL_LOG_TRUNCATED.head()
                                + "guard denial log for {} holds more than {} events; further denials are truncated",
                        key,
                        MAX_EVENTS);
                break;
            }
            Finding finding = parse(
                    line.substring(marker + EgressGuardConfig.DENY_MARKER.length())
                            .strip(),
                    drops);
            if (finding != null) {
                findings.add(finding);
            }
        }
        drops.report(key);
        return List.copyOf(findings);
    }

    /**
     * The two ways one marked line fails to become a finding, counted across a single read. One
     * line per read, whatever the volume: the count is what tells an operator whether they are
     * looking at one odd event or a guard whose whole output the factory no longer parses. The
     * first malformed line's reason rides along so the aggregate is still diagnosable.
     */
    private static final class Drops {

        private int malformed;
        private int withoutHost;
        private @Nullable String firstReason;

        void malformed(String reason) {
            malformed++;
            if (firstReason == null) {
                firstReason = reason;
            }
        }

        void withoutHost() {
            withoutHost++;
        }

        void report(String key) {
            if (malformed + withoutHost == 0) {
                return;
            }
            log.warn(
                    OperatorEvent.GUARD_DENIAL_EVENTS_DROPPED.head()
                            + "dropped {} unparseable guard denial event(s) for {} ({} malformed, {} without a host);"
                            + " these denials are missing from the findings. First malformed reason: {}",
                    malformed + withoutHost,
                    key,
                    malformed,
                    withoutHost,
                    firstReason == null ? "none" : firstReason);
        }
    }

    /**
     * One denial event to one finding: message names the denied destination,
     * location carries host:port plus the path when one exists (UX3), details
     * carry the request kind and method. Returns null for a line that is not the well-formed
     * metadata object the addon emits, counting the drop in {@code drops} — the read's caller
     * reports them together.
     *
     * <p>The path is cut at its query string first (NFR-S1 of
     * fix-denial-report-attachment): a denied {@code GET /upload?token=…} is the
     * gnome's own exfiltration payload, and the finding is committed to the task
     * branch, so only the destination-side part of the path travels.
     */
    private static @Nullable Finding parse(String json, Drops drops) {
        JsonNode event;
        try {
            event = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            // throwable-not-subject: the aggregate carries the reason as text because one line
            //     stands for many drops; a stack per malformed guard line is the flood D4 removes.
            drops.malformed(LogText.forLog(String.valueOf(e.getOriginalMessage())));
            return null;
        }
        String host = capped(event.path("host").asText(""));
        if (host.isBlank()) {
            drops.withoutHost();
            return null;
        }
        String destination = host + portSuffix(event);
        String path = capped(withoutQuery(event.path("path").asText("")));
        String method = capped(event.path("method").asText(""));
        String kind = capped(event.path("kind").asText("connect"));
        return new Finding(
                "egress denied: " + destination,
                path.isEmpty() ? destination : destination + path,
                method.isEmpty() ? "kind=" + kind : "kind=" + kind + " method=" + method);
    }

    /**
     * The path up to its query string (NFR-S1): the query is request payload the
     * gnome chose, not metadata about the destination it was denied.
     */
    private static String withoutQuery(String path) {
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    private static String portSuffix(JsonNode event) {
        JsonNode port = event.path("port");
        return port.canConvertToInt() ? ":" + port.asInt() : "";
    }

    // Branch-free on purpose: a length conditional here only spawns boundary mutants that are
    // behaviorally equivalent at exactly MAX_FIELD_LENGTH (substring of the full length is the
    // same string), which the mutation gate cannot kill.
    private static String capped(String value) {
        return value.substring(0, Math.min(value.length(), MAX_FIELD_LENGTH));
    }
}
