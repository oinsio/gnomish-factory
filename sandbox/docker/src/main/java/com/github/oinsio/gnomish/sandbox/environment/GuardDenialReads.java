package com.github.oinsio.gnomish.sandbox.environment;

import com.github.oinsio.gnomish.domain.engine.Finding;
import com.github.oinsio.gnomish.logtext.LogText;
import com.github.oinsio.gnomish.sandbox.DenialCursor;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The denial read-back of one {@link EgressGuard} (NFR-O1, D3 of
 * fix-denial-report-attachment): the daemon-side {@code --since} cursor, the
 * bounded log read it drives, and the durable cursor a resume hands back.
 * Extracted from {@link EgressGuard} for file size; the guard keeps the
 * container lifecycle.
 *
 * <p>Consecutive reads return disjoint slices, so a round asking at its close is
 * told its own denials and never an earlier round's. The cursor is a daemon
 * timestamp, immune to in-box clock skew; a failed read leaves it where it was,
 * so nothing is lost to a transient docker outage.
 *
 * <p>Across processes the cursor travels through {@code state.json} (FR5): the
 * guard container outlives a lease, so a resume that reattaches to a surviving
 * container would otherwise re-read its whole log tail and re-attach rounds that
 * already committed their own denials. A restored position is applied only when
 * it names the live container — matched by runtime id, since a position stamped
 * by another machine's daemon clock, or by a container since recreated, could
 * filter real denials out of the report instead.
 *
 * <p>Implements NFR-O1, NFR-R1, FR5 of fix-denial-report-attachment.
 */
final class GuardDenialReads {

    private static final Logger log = LoggerFactory.getLogger(GuardDenialReads.class);

    private static final int LOG_TAIL_LINES = 1000;

    private final DockerCli docker;
    private final String key;

    /** The daemon-side lower bound of the next read — null means "from container start" (D3). */
    private @Nullable String since;

    /** A cursor committed by an earlier lease, awaiting the source match of the first read (FR5). */
    private @Nullable DenialCursor offered;

    /** The live container's runtime id, re-probed after {@link #sourceRecreated()}. */
    private @Nullable String sourceId;

    GuardDenialReads(DockerCli docker, String key) {
        this.docker = docker;
        this.key = key;
    }

    /** Accepts a cursor from an earlier lease; applied — or rejected — at the first read (FR5). */
    synchronized void restore(DenialCursor cursor) {
        offered = cursor;
    }

    /**
     * The position to commit with the attempt this read delimits, paired with the
     * container it was read from. Empty until a read has actually advanced the
     * cursor, or when the container's id cannot be read — a position with no
     * identifiable source is one a later lease must not apply.
     */
    synchronized Optional<DenialCursor> cursor() {
        String position = since;
        if (position == null) {
            return Optional.empty();
        }
        String source = sourceId();
        return source == null ? Optional.empty() : Optional.of(new DenialCursor(source, position));
    }

    /** Invalidates the cached container id: a recreated guard is a different denial source. */
    synchronized void sourceRecreated() {
        sourceId = null;
    }

    /** The denials recorded since the previous read; see {@link EgressGuard#denialFindings()}. */
    synchronized List<Finding> findings() {
        applyOfferedCursor();
        DockerResult logs;
        try {
            logs = docker.run(GuardCommands.guardLogs(key, LOG_TAIL_LINES, since));
        } catch (DockerUnavailableException e) {
            // The runtime outage classification (NFR-R1) applies to work the factory still owes;
            // a denial read is pure observability of work already finished, so an unreachable
            // daemon here is silence with the cursor left where it was — never a thrown round.
            log.warn("could not read egress guard log for {}", key, e);
            return List.of();
        }
        if (!logs.ok()) {
            log.warn("could not read egress guard log for {}: {}", key, LogText.forLog(logs.stderr()));
            return List.of();
        }
        if (GuardLogCursor.saturated(logs.stdout(), LOG_TAIL_LINES)) {
            log.warn(
                    "egress guard log read for {} filled its {}-line tail window; older lines of this"
                            + " window were dropped before parsing and are not in the findings (NFR-O1)",
                    key,
                    LOG_TAIL_LINES);
        }
        String advanced = GuardLogCursor.advance(logs.stdout());
        if (advanced != null) {
            since = advanced;
        }
        return GuardDenialLog.findings(key, logs.stdout());
    }

    /**
     * Consumes a restored cursor once, before the first read: applied when it names
     * the live container, dropped with a log line when it names another source (a
     * resume on a different machine, or onto a recreated container) — reading that
     * source from its start is then correct, since its log holds no round the
     * factory already reported.
     */
    private void applyOfferedCursor() {
        DenialCursor cursor = offered;
        if (cursor == null) {
            return;
        }
        offered = null;
        String source = sourceId();
        if (cursor.source().equals(source)) {
            since = cursor.position();
            return;
        }
        log.info(
                "committed denial cursor for {} was read from guard container {}, not the live {} —"
                        + " reading its log from the start (FR5)",
                key,
                cursor.source(),
                source == null ? "(unreadable)" : source);
    }

    /** The guard container's runtime id, cached; null when it cannot be read (best-effort, NFR-R1). */
    private @Nullable String sourceId() {
        String cached = sourceId;
        if (cached != null) {
            return cached;
        }
        DockerResult probe;
        try {
            probe = docker.run(GuardCommands.inspectGuardId(key));
        } catch (DockerUnavailableException e) {
            // No source id means no committable cursor: the next lease replays every denial still
            // in this guard's log rather than reading its own slice (FR5). DEBUG — the round's
            // own findings are unaffected, only the cross-process de-duplication is.
            log.debug("egress guard id for {} is unreadable; this attempt commits no denial cursor", key, e);
            return null;
        }
        String id = probe.stdout().strip();
        if (!probe.ok() || id.isEmpty()) {
            // throwable-not-subject: docker answered; the answer is simply not an id.
            log.debug("egress guard id for {} came back empty; this attempt commits no denial cursor", key);
            return null;
        }
        sourceId = id;
        return id;
    }
}
