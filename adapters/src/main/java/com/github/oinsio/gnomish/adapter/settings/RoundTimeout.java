package com.github.oinsio.gnomish.adapter.settings;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The one owner of the {@code roundTimeout} settings key (design D6 of extract-agent-round-runner):
 * the shapes it accepts, judged at law load, and the round bound it resolves to at round time — one
 * parse behind both, so the judge and the resolver cannot drift. A shape is a {@link Number} of
 * seconds or a non-blank ISO-8601 duration string, with or without the {@code PT} prefix,
 * case-insensitively ({@code 45}, {@code "PT30S"}, {@code "45s"}).
 *
 * <p>Callers: the Claude dialect's settings judge and {@code CommandSettingsValidator} at load; the
 * agent-cli rounds and the {@code command} executor at round time. The value is never rendered as a
 * CLI flag: it bounds the process from the factory's side.
 *
 * <p>The resolver is tolerant, for a settings map the loader never judged: an absent or malformed
 * value falls back to {@link #DEFAULT}, and a blank string resolves to zero, as it always has (the
 * load-time judge refuses both, so a loaded law carries neither).
 *
 * <p>Was the declared pair of {@code RoundTimeout} in {@code :adapters:agent} and {@code
 * AgentSettingsValidator}, each holding its own copy of the shapes; the pair dissolved into this
 * class (shared abstraction, D6, D7 of extract-agent-round-runner).
 *
 * <p>Implements FR11, FR13, D7 of add-agent-executor; FR3 of add-command-executor; FR7, D6 of
 * extract-agent-round-runner.
 */
public final class RoundTimeout {

    /** The settings key. */
    public static final String KEY = "roundTimeout";

    /** The round bound when {@code roundTimeout} is absent or malformed. */
    public static final Duration DEFAULT = Duration.ofMinutes(30);

    private RoundTimeout() {}

    /**
     * Resolves the round bound from {@code settings}.
     *
     * @param settings an executor's or judge check's settings; never null, may be empty
     * @return the bound; never null
     */
    public static Duration resolve(Map<String, ?> settings) {
        Object raw = settings.get(KEY);
        if (raw instanceof String text && text.isBlank()) {
            return Duration.ZERO;
        }
        Duration parsed = parsed(raw);
        return parsed != null ? parsed : DEFAULT;
    }

    /**
     * The malformed-value message of a {@code roundTimeout} value, or {@code null} when it is
     * well-formed — the one load-time judge of the accepted shapes.
     *
     * @param value the value as the YAML tree produced it; may be null
     * @return the message, or {@code null} when well-formed
     */
    public static @Nullable String malformation(@Nullable Object value) {
        return parsed(value) != null
                ? null
                : "malformed '" + KEY + "': expected a number of seconds or an ISO-8601 duration string";
    }

    /** The duration {@code value} names, or {@code null} when it has no accepted shape. */
    private static @Nullable Duration parsed(@Nullable Object value) {
        if (value instanceof Number number) {
            return Duration.ofSeconds(number.longValue());
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return null;
        }
        String withPrefix =
                text.startsWith("PT") || text.startsWith("pt") ? text : "PT" + text.toUpperCase(Locale.ROOT);
        try {
            return Duration.parse(withPrefix);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
