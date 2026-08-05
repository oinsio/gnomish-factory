package com.github.oinsio.gnomish.serveobservability.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code lifecycle} ledger line (v1, spec.md): {@code
 * version} (always {@code 1}), {@code type} (always {@code "lifecycle"}),
 * {@code instance}, {@code at}, {@code event} ({@code started} or {@code
 * stopped}), and {@code reason} (present only when {@code event} is {@code
 * stopped}) (FR10, FR12).
 *
 * <p>Implements FR10, FR12 conventions of add-serve-observability.
 *
 * @param version the contract version; always {@code 1}
 * @param type the line-type discriminator; always {@code "lifecycle"}
 * @param instance the writing process's identity
 * @param at ISO-8601 UTC instant the event occurred
 * @param event {@code started} or {@code stopped}
 * @param reason why the daemon stopped; present only when {@code event} is {@code stopped}
 */
public record LifecycleLineDto(
        int version,
        String type,
        InstanceDto instance,
        String at,
        String event,
        @Nullable String reason) {}
