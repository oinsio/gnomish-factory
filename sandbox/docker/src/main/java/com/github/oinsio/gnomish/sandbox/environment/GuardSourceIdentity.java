package com.github.oinsio.gnomish.sandbox.environment;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Who the denial source <em>is</em>: the guard container's runtime id, which stamps every read
 * position (FR5 of fix-denial-report-attachment) and every denial identity (FR7 of
 * fix-denial-attribution-durability).
 *
 * <p>A class of its own because the answer is a decision, not a field: the id is probed from the
 * daemon, cached until the guard is recreated, and may legitimately be unavailable — and an
 * unavailable id is what makes a read commit no position and stamp no identity, so a later lease
 * re-reads rather than trusting a position it cannot attribute. Keeping that policy in one place
 * keeps {@link GuardDenialReads} about reading.
 *
 * <p>Best-effort throughout (NFR-R1): a daemon that will not answer costs cross-process
 * de-duplication, never the round's own findings.
 */
final class GuardSourceIdentity {

    private static final Logger log = LoggerFactory.getLogger(GuardSourceIdentity.class);

    private final DockerCli docker;
    private final String key;

    /** The live container's runtime id, re-probed after {@link #recreated()}. */
    private @Nullable String sourceId;

    GuardSourceIdentity(DockerCli docker, String key) {
        this.docker = docker;
        this.key = key;
    }

    /** Invalidates the cached id: a recreated guard is a different denial source. */
    void recreated() {
        sourceId = null;
    }

    /**
     * The identity, probed at most once between recreations; null when it cannot be read
     * (best-effort, NFR-R1).
     *
     * @return the guard container's runtime id, or null when docker will not answer
     */
    @Nullable
    String current() {
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
