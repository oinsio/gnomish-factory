package com.github.oinsio.gnomish.adapter.settings;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The one owner of the {@code roundTimeout} settings key (design D6 of extract-agent-round-runner):
 * the shapes it accepts, judged at law load, and the duration a settings map declares at round time —
 * one parse behind both, so the judge and the reader cannot drift. A shape is a {@link Number} of
 * seconds or a non-blank ISO-8601 duration string, with or without the {@code PT} prefix,
 * case-insensitively ({@code 45}, {@code "PT30S"}, {@code "45s"}), naming a positive duration: a zero
 * or negative bound is refused at load rather than handed to a round, where {@code ProgramBounds}
 * would refuse it on every round of the stage.
 *
 * <p>Callers: the Claude dialect's settings judge and {@code CommandSettingsValidator} at load;
 * {@code RoundBounds} in {@code :adapters:agent} at round time, the one resolution of a round's
 * bounds, which every round reaches it through. The value is never rendered as a CLI flag: it bounds
 * the process from the factory's side.
 *
 * <p>The reader answers "declared or not" and holds no default of its own (D6 as amended
 * 2026-11-21): the factory's overall bound a round takes when nothing is declared is written once, in
 * {@code ProgramBounds.DEFAULTS}, and applied by {@code RoundBounds}. A value with no accepted shape
 * declares nothing — the load-time judge refuses it, so a loaded law carries none.
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

    private RoundTimeout() {}

    /**
     * The {@code roundTimeout} {@code settings} declare.
     *
     * @param settings an executor's or judge check's settings; never null, may be empty
     * @return the declared duration, or empty when the key is absent or has no accepted shape; never
     *     null
     */
    public static Optional<Duration> declared(Map<String, ?> settings) {
        return Optional.ofNullable(parsed(settings.get(KEY)));
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
                : "malformed '" + KEY
                        + "': expected a positive number of seconds or a positive ISO-8601 duration string";
    }

    /** The positive duration {@code value} names, or {@code null} when it has no accepted shape. */
    private static @Nullable Duration parsed(@Nullable Object value) {
        Duration duration = shaped(value);
        return duration == null || duration.isNegative() || duration.isZero() ? null : duration;
    }

    /** The duration {@code value} spells, of any sign, or {@code null} when it has no accepted shape. */
    private static @Nullable Duration shaped(@Nullable Object value) {
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
