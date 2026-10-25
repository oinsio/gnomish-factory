package com.github.oinsio.gnomish.app.serve;

import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import java.time.Instant;

/**
 * One closed episode's summary, handed to {@link DaemonCondition}'s {@code onClosedOutage}
 * callback exactly once per episode — the {@code daemonCondition} ledger line's own fields
 * (serve-observability spec of add-base-ref-resolution, task 7.4; design D19 of
 * add-sandbox-base-image).
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O1, NFR-O3 of add-base-ref-resolution; NFR-O2 of add-sandbox-base-image.
 *
 * @param kind the condition this episode belonged to; never null
 * @param target the remote target identity this outage belonged to; never null
 * @param openedAt when the gate opened; never null
 * @param closedAt when the gate closed; never null
 * @param probeCount how many probes failed while the gate was open (excludes the closing,
 *     successful one)
 * @param releasedClaims how many claims were released by an open gate during this outage, before
 *     any could be attempted
 * @param lastError the last known failure reason, scrubbed; never null
 */
public record RemoteOutageClosedOutage(
        DaemonConditionKind kind,
        String target,
        Instant openedAt,
        Instant closedAt,
        int probeCount,
        int releasedClaims,
        String lastError) {}
