package com.github.oinsio.gnomish.serveobservability.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code remote[target]} entry (NFR-O3, UX6 of add-base-ref-resolution):
 * {@code state} ({@code "open"} | {@code "closed"}), {@code openSince}, {@code lastError}, {@code
 * nextProbeAt}, {@code consecutiveFailures}, {@code lastSuccessAt}.
 *
 * @param state the gate's state, {@code "open"} or {@code "closed"}
 * @param openSince ISO-8601 UTC instant the gate opened; {@code null} while closed
 * @param lastError the scrubbed cause of the last failed fetch or probe; {@code null} while closed
 * @param nextProbeAt ISO-8601 UTC instant the next probe is scheduled; {@code null} while closed
 * @param consecutiveFailures failed fetches and probes since the gate opened; zero while closed
 * @param lastSuccessAt ISO-8601 UTC instant of the last successful fetch or probe; {@code null} if
 *     none yet
 */
public record RemoteDto(
        String state,
        @Nullable String openSince,
        @Nullable String lastError,
        @Nullable String nextProbeAt,
        int consecutiveFailures,
        @Nullable String lastSuccessAt) {}
