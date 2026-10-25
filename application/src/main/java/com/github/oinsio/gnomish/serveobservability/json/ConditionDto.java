package com.github.oinsio.gnomish.serveobservability.json;

import org.jspecify.annotations.Nullable;

/**
 * The JSON contract's {@code conditions[]} entry (design D19, NFR-O2, UX4 of
 * add-sandbox-base-image): the {@code remote[target]} entry's fields, named by the condition's
 * token, with the remote's {@code target}.
 *
 * @param condition the condition's wire token ({@code ConditionTokens}); a newer factory's token
 *     is kept as read
 * @param target the remote's target; {@code null} for every other condition
 * @param state the condition's state, {@code "open"} or {@code "closed"}
 * @param openSince ISO-8601 UTC instant it opened; {@code null} while closed
 * @param lastError the last cause; {@code null} while closed
 * @param nextProbeAt ISO-8601 UTC instant the next probe is due; {@code null} while closed
 * @param consecutiveFailures failures since it opened; zero while closed
 * @param lastSuccessAt ISO-8601 UTC instant the precondition was last seen met; {@code null} if
 *     never
 */
public record ConditionDto(
        String condition,
        @Nullable String target,
        String state,
        @Nullable String openSince,
        @Nullable String lastError,
        @Nullable String nextProbeAt,
        int consecutiveFailures,
        @Nullable String lastSuccessAt) {}
