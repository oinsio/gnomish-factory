package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * One remote target's entry in the snapshot's {@code remote} section (NFR-O3, UX6 of
 * add-base-ref-resolution): the remote outage gate's current state, at a glance.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O3, UX6 of add-base-ref-resolution.
 *
 * @param target the remote target identity this entry reports on; never blank
 * @param open whether the gate currently blocks a claim
 * @param openSince when the gate opened; null while closed
 * @param lastError the scrubbed cause of the last failed fetch or probe; null while closed
 * @param nextProbeAt when the next probe is scheduled; null while closed
 * @param consecutiveFailures failed fetches and probes since the gate opened; zero while closed
 * @param lastSuccessAt the last successful fetch or probe; null if none yet
 */
public record RemoteHealth(
        String target,
        boolean open,
        @Nullable Instant openSince,
        @Nullable String lastError,
        @Nullable Instant nextProbeAt,
        int consecutiveFailures,
        @Nullable Instant lastSuccessAt) {}
