package com.github.oinsio.gnomish.adapter.pipeline;

import com.github.oinsio.gnomish.adapter.settings.RoundTimeout;
import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Startup-time settings-schema validator for {@code command} stages: a command executor's
 * {@code settings} map accepts exactly {@code roundTimeout}, in the shapes the agent-cli rounds
 * accept. Every other key, and a malformed {@code roundTimeout}, is a located {@link ConfigError}
 * naming the stage manifest and {@code executor.settings.<key>}, reported before any stage runs
 * (UX2).
 *
 * <p>The schema of the other executor types is not judged here: every {@code agent-cli} executor
 * block, a {@code judge} check's included, belongs to {@link AgentSettingsValidator}; a judge's
 * {@code command} block is refused at load for its role (no settings of it run). The {@code roundTimeout} shapes are judged by their one owner, {@link
 * RoundTimeout}, which the Claude dialect's settings judge calls too (design D6 of
 * extract-agent-round-runner), never by a copy here.
 *
 * <p>Implements FR3, UX2 of add-command-executor; D6 of extract-agent-round-runner.
 */
final class CommandSettingsValidator {

    private CommandSettingsValidator() {}

    /**
     * Validates every {@code command} stage executor's settings across the whole pipeline.
     *
     * <p>Implements FR3, UX2 of add-command-executor.
     *
     * @param pipeline the mapped, domain-validated pipeline definition
     * @return every located settings problem, in stage order and key-name order within a stage; immutable, possibly empty
     */
    static List<ConfigError> validate(PipelineDefinition pipeline) {
        List<ConfigError> errors = new ArrayList<>();
        for (StageDefinition stage : pipeline.stages()) {
            if (stage.executor().type() == ExecutorType.COMMAND) {
                validateSettings(
                        "stages/%s/stage.yaml".formatted(stage.name()),
                        stage.executor().settings(),
                        errors);
            }
        }
        return List.copyOf(errors);
    }

    private static void validateSettings(String manifest, Map<String, Object> settings, List<ConfigError> errors) {
        // Keys in name order: the settings map is unordered (Map.copyOf), the report must not be.
        for (Map.Entry<String, Object> entry : new TreeMap<>(settings).entrySet()) {
            String key = entry.getKey();
            String where = "executor.settings." + key;
            String problem = RoundTimeout.KEY.equals(key)
                    ? RoundTimeout.malformation(entry.getValue())
                    : "unrecognized settings key '%s'".formatted(key);
            if (problem != null) {
                errors.add(new ConfigError(manifest, where, problem));
            }
        }
    }
}
