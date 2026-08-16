package com.github.oinsio.gnomish.app.lease;

/**
 * {@link InstanceHeartbeat}'s own reported state (task 2.5, add-serve-observability FR7): {@code
 * IDLE} before the first claim or after a normal empty-and-stop, {@code RUNNING} while the worker
 * thread is beating held claims, {@code DIED} after {@link InstanceHeartbeat#onWorkerDeath} — kept
 * distinct from {@code serveobservability}'s {@code HeartbeatState} so this package carries no
 * compile-time dependency on the observability document model (mirrors {@code FeedState}'s
 * rationale for {@code FeedPhase}).
 *
 * <p>Implements FR7 of add-serve-observability.
 */
public enum HeartbeatWorkerState {
    IDLE,
    RUNNING,
    DIED
}
