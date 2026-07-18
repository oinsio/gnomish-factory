package com.github.oinsio.gnomish.adapter.agent;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a round's {@code roundTimeout} process-kill budget from a stage
 * executor's or judge check's opaque {@code settings} map (design D7):
 * {@code roundTimeout} is a recognized settings key (FR11) but is never
 * rendered as a CLI flag ({@link AgentInvocationOptions}) — it is
 * engine/adapter-side process timeout enforcement instead, consumed by
 * {@link LaunchedAgentProcess#waitForExitOrTimeout}.
 *
 * <p>The pipeline-config loader never coerces this key into a typed {@link
 * Duration} — {@link AgentSettingsValidator} (task 9.1) only checks the raw
 * value's shape is well-formed at startup, it does not rewrite the map — so
 * the value still arrives here as whatever plain JDK type the YAML tree
 * produced: a {@link Number} of seconds, or an ISO-8601 duration string such
 * as {@code "PT30S"}. This class accepts both, tolerantly, mirroring exactly
 * what {@link AgentSettingsValidator} already accepted as well-formed: an
 * absent key falls back to {@link #DEFAULT}, since an {@code api} stage or a
 * manifest predating the validator must not silently hang a round forever.
 *
 * <p>Implements FR11, FR13, D7 of add-agent-executor.
 */
final class RoundTimeout {

    private static final String KEY = "roundTimeout";

    /** The adapter's fallback budget when {@code roundTimeout} is absent or unparseable. */
    static final Duration DEFAULT = Duration.ofMinutes(30);

    private RoundTimeout() {}

    /**
     * Resolves the timeout budget from {@code settings}.
     *
     * @param settings the executor's or judge check's opaque settings map;
     *     never null, may be empty
     * @return the resolved timeout budget; never null, never negative
     */
    static Duration resolve(Map<String, Object> settings) {
        Object raw = settings.get(KEY);
        if (raw instanceof Number number) {
            return Duration.ofSeconds(number.longValue());
        }
        if (raw instanceof String text) {
            return parseIso(text);
        }
        return DEFAULT;
    }

    private static Duration parseIso(String text) {
        try {
            return Duration.parse(withPrefix(text));
        } catch (DateTimeParseException e) {
            return DEFAULT;
        }
    }

    private static String withPrefix(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return "PT0S";
        }
        return text.startsWith("PT") || text.startsWith("pt") ? text : "PT" + text.toUpperCase(java.util.Locale.ROOT);
    }
}
