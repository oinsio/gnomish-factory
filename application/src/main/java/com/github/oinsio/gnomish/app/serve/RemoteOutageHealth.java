package com.github.oinsio.gnomish.app.serve;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A read-only view of one {@link RemoteOutageGate}'s current state, for the snapshot's {@code
 * remote} section (serve-observability spec of add-base-ref-resolution, task 7.4) — deliberately
 * its own small type rather than exposing the gate's fields directly, so a reader never sees a
 * state combination the gate itself never produces.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O3, UX6 of add-base-ref-resolution.
 *
 * @param target the remote target identity; never null
 * @param open whether the gate currently blocks a claim
 * @param openSince when the gate opened; null while closed
 * @param lastError the scrubbed cause of the last failed fetch or probe; null while closed
 * @param nextProbeAt when the next probe is scheduled; null while closed
 * @param consecutiveFailures failed fetches and probes since the gate opened; zero while closed
 * @param lastSuccessAt the last successful fetch or probe; null if none yet
 */
public record RemoteOutageHealth(
        String target,
        boolean open,
        @Nullable Instant openSince,
        @Nullable String lastError,
        @Nullable Instant nextProbeAt,
        int consecutiveFailures,
        @Nullable Instant lastSuccessAt) {}
