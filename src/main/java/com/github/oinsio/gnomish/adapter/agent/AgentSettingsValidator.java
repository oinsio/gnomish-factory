package com.github.oinsio.gnomish.adapter.agent;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.domain.pipeline.VerifyCheck;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Startup-time settings-schema validator for the CLI adapters (task 9.1):
 * every {@code agent-cli} stage executor's {@code settings} map, and every
 * {@code judge} check's {@code settings} map regardless of its parent
 * stage's executor type, must contain only the four recognized keys —
 * {@code allowedTools}, {@code disallowedTools}, {@code maxTurns},
 * {@code roundTimeout} — with well-formed values, matching exactly what
 * {@link AgentInvocationOptions} and {@link RoundTimeout} consume at round
 * time. A judge check always launches its own {@code agent-cli} round (FR8
 * of add-agent-executor) independent of the stage's own executor, so its
 * settings are validated unconditionally — an {@code api} stage's judge
 * check is not exempt (FR11, D7).
 *
 * <p>{@code api} stage executor settings are never inspected: {@code api}
 * stages are rejected at startup by a separate fail-fast check (task 9.2,
 * FR10); validating their opaque settings here would be validating a shape
 * this change never consumes.
 *
 * <p>This validator is owned by the adapter package, not the domain: the
 * pipeline loader and domain model stay opaque to executor-settings shape
 * (design D5a of load-pipeline-config) — {@code StageSanityRule} explicitly
 * does not validate settings keys, values, or ranges. Errors are located
 * {@link ConfigError}s naming the stage manifest and the offending
 * {@code executor.settings.<key>} or {@code verify[n].settings.<key>} path,
 * surfaced before any dialog (UX2) once wired into the loader.
 *
 * <p>Implements FR11, UX2, D7 of add-agent-executor.
 */
public final class AgentSettingsValidator {

    private static final String ALLOWED_TOOLS_KEY = "allowedTools";
    private static final String DISALLOWED_TOOLS_KEY = "disallowedTools";
    private static final String MAX_TURNS_KEY = "maxTurns";
    private static final String ROUND_TIMEOUT_KEY = "roundTimeout";

    private static final Set<String> RECOGNIZED_KEYS =
            Set.of(ALLOWED_TOOLS_KEY, DISALLOWED_TOOLS_KEY, MAX_TURNS_KEY, ROUND_TIMEOUT_KEY);

    private AgentSettingsValidator() {}

    /**
     * Validates every {@code agent-cli} stage executor's settings and every
     * judge check's settings across the whole pipeline.
     *
     * <p>Implements FR11, UX2, D7 of add-agent-executor.
     *
     * @param pipeline the mapped, domain-validated pipeline definition
     * @return every located settings problem, in stage-then-check order;
     *     immutable, possibly empty
     */
    public static List<ConfigError> validate(PipelineDefinition pipeline) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : pipeline.stages()) {
            validateStage(stage, errors);
        }
        return List.copyOf(errors);
    }

    private static void validateStage(StageDefinition stage, List<ConfigError> errors) {
        String manifest = manifestPath(stage.name());
        if (stage.executor().type() == ExecutorType.AGENT_CLI) {
            validateSettings(manifest, "executor.settings", stage.executor().settings(), errors);
        }
        List<VerifyCheck> checks = stage.verify();
        for (int index = 0; index < checks.size(); index++) {
            if (checks.get(index) instanceof VerifyCheck.Judge judge) {
                validateSettings(manifest, "verify[%d].settings".formatted(index), judge.settings(), errors);
            }
        }
    }

    private static void validateSettings(
            String manifest, String where, Map<String, Object> settings, List<ConfigError> errors) {
        for (Map.Entry<String, Object> entry : settings.entrySet()) {
            String key = entry.getKey();
            if (!RECOGNIZED_KEYS.contains(key)) {
                errors.add(
                        new ConfigError(manifest, where + "." + key, "unrecognized settings key '%s'".formatted(key)));
                continue;
            }
            String problem = malformationOf(key, entry.getValue());
            if (problem != null) {
                errors.add(new ConfigError(manifest, where + "." + key, problem));
            }
        }
    }

    /** Returns the malformed-value message for {@code key}/{@code value}, or {@code null} when well-formed. */
    private static @Nullable String malformationOf(String key, @Nullable Object value) {
        return switch (key) {
            case ALLOWED_TOOLS_KEY, DISALLOWED_TOOLS_KEY ->
                isStringList(value) ? null : "malformed '%s': expected a list of strings".formatted(key);
            case MAX_TURNS_KEY -> value instanceof Number ? null : "malformed 'maxTurns': expected a number";
            case ROUND_TIMEOUT_KEY ->
                isWellFormedRoundTimeout(value)
                        ? null
                        : "malformed 'roundTimeout': expected a number of seconds or an ISO-8601 duration string";
            default -> throw new IllegalStateException("unreachable: key already filtered by RECOGNIZED_KEYS");
        };
    }

    private static boolean isStringList(@Nullable Object value) {
        if (!(value instanceof List<?> list)) {
            return false;
        }
        for (Object element : list) {
            if (!(element instanceof String)) {
                return false;
            }
        }
        return true;
    }

    /** Mirrors {@link RoundTimeout#resolve}'s accepted shapes exactly: a {@link Number}, or a parseable ISO-8601 string. */
    private static boolean isWellFormedRoundTimeout(@Nullable Object value) {
        if (value instanceof Number) {
            return true;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            return false;
        }
        String withPrefix =
                text.startsWith("PT") || text.startsWith("pt") ? text : "PT" + text.toUpperCase(Locale.ROOT);
        try {
            Duration.parse(withPrefix);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static String manifestPath(String stageName) {
        return "stages/%s/stage.yaml".formatted(stageName);
    }
}
