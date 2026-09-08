package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's {@code remoteOutage} ledger line (NFR-O1, NFR-O3 of
 * add-base-ref-resolution): one closed remote outage gate episode, with the target, when it opened
 * and closed, its duration, how many probes failed, how many claims it released, and the last
 * known failure reason.
 *
 * @param version the contract version; always {@code 1}
 * @param type the line-type discriminator; always {@code "remoteOutage"}
 * @param instance the writing process's identity
 * @param target the remote target identity this outage belonged to
 * @param openedAt ISO-8601 UTC instant the gate opened
 * @param closedAt ISO-8601 UTC instant the gate closed
 * @param durationMillis the outage's duration in milliseconds
 * @param probeCount how many probes failed while the gate was open
 * @param releasedClaims how many claims were released by the open gate
 * @param lastError the last known failure reason, scrubbed
 */
public record RemoteOutageLineDto(
        int version,
        String type,
        InstanceDto instance,
        String target,
        String openedAt,
        String closedAt,
        long durationMillis,
        int probeCount,
        int releasedClaims,
        String lastError) {}
