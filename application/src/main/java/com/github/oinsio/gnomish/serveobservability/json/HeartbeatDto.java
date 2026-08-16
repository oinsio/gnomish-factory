package com.github.oinsio.gnomish.serveobservability.json;

/**
 * The JSON contract's {@code vitals.heartbeat} entry (FR7): {@code state}
 * (serialized {@code idle | running | died}), {@code lastTickAt}, {@code
 * heldClaims}.
 *
 * @param state one of {@code idle | running | died}
 * @param lastTickAt ISO-8601 UTC instant of the last heartbeat tick
 * @param heldClaims the number of claims the heartbeat currently keeps alive
 */
public record HeartbeatDto(String state, String lastTickAt, int heldClaims) {}
