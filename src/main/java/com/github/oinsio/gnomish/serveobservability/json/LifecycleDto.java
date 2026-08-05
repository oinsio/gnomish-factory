package com.github.oinsio.gnomish.serveobservability.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code lifecycle} section: {@code state} is one of
 * {@code running | draining | stopping | stopped}; {@code reason} is present
 * only when {@code state} is {@code stopped}, rendering explicit JSON {@code
 * null} otherwise (spec.md convention) rather than a discriminated-subtype
 * shape — the only variant-specific data is this one optional field.
 *
 * @param state one of {@code running | draining | stopping | stopped}
 * @param reason why the daemon stopped, or {@code null} unless {@code
 *     state} is {@code stopped}
 */
public record LifecycleDto(String state, @Nullable String reason) {}
