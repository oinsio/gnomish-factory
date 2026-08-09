package com.github.oinsio.gnomish.serveobservability;

/**
 * The claim-heartbeat worker's reported state (FR7): {@code idle | running |
 * died}. Three-valued rather than a timestamp-only health check so a dead
 * heartbeat is a distinct value, not inferred from staleness alone — the
 * heartbeat's designed degradation death path becomes a field, not just an
 * ERROR log line (design D3).
 *
 * <p>Implements FR7 of add-serve-observability.
 */
public enum HeartbeatState {
    IDLE,
    RUNNING,
    DIED
}
