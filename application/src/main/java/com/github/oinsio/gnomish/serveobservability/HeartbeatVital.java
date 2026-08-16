package com.github.oinsio.gnomish.serveobservability;

import java.time.Instant;

/**
 * The snapshot's {@code vitals.heartbeat} entry (FR7): the claim-heartbeat
 * worker's {@link HeartbeatState}, its last tick time, and how many claims
 * it currently holds.
 *
 * <p>Inert value data compared by content.
 *
 * <p>Implements FR7 of add-serve-observability.
 *
 * @param state the heartbeat worker's current state; never null
 * @param lastTickAt the last time the heartbeat ticked; never null
 * @param heldClaims the number of claims the heartbeat currently keeps alive
 */
public record HeartbeatVital(HeartbeatState state, Instant lastTickAt, int heldClaims) {}
