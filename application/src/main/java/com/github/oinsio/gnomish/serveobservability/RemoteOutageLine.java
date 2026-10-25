package com.github.oinsio.gnomish.serveobservability;

import com.github.oinsio.gnomish.app.take.DaemonConditionKind;
import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A ledger {@code daemonCondition} line (the former {@code remoteOutage} line): one closed daemon
 * condition episode, the remote outage gate's among them (NFR-O1, NFR-O3 of
 * add-base-ref-resolution) — appended exactly once when the gate closes, never per failure or per
 * probe, mirroring {@link SweepActionLine}'s "one line per action, not per polled object" rule.
 *
 * <p>A gate still open at daemon stop produces no line: the snapshot's final {@code stopped}
 * record already carries the open state, and the next daemon starts with a closed gate that knows
 * nothing of the unfinished outage.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements NFR-O1, NFR-O3 of add-base-ref-resolution.
 *
 * @param instance the writing process's identity; never null
 * @param condition the condition the episode belonged to; never null
 * @param target the remote target identity; null for every condition but the remote
 * @param openedAt when the gate opened; never null
 * @param closedAt when the gate closed; never null, not before {@code openedAt}
 * @param duration how long the outage lasted, {@code closedAt - openedAt}; never null
 * @param probeCount how many probes failed while the gate was open; never negative
 * @param releasedClaims how many claims were released by the open gate before any could be
 *     attempted; never negative
 * @param lastError the last known failure reason, scrubbed; never blank
 */
public record RemoteOutageLine(
        InstanceInfo instance,
        DaemonConditionKind condition,
        @Nullable String target,
        Instant openedAt,
        Instant closedAt,
        Duration duration,
        int probeCount,
        int releasedClaims,
        String lastError)
        implements LedgerLine {}
