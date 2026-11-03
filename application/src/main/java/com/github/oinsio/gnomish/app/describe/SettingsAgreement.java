package com.github.oinsio.gnomish.app.describe;

import com.github.oinsio.gnomish.domain.pipeline.ConfigError;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorDeclaration;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorSettings;
import com.github.oinsio.gnomish.domain.pipeline.ExecutorType;
import com.github.oinsio.gnomish.domain.pipeline.PipelineDefinition;
import com.github.oinsio.gnomish.domain.pipeline.StageDefinition;
import com.github.oinsio.gnomish.executorcontract.Describe;
import com.github.oinsio.gnomish.executorcontract.SettingsJudgement;
import com.github.oinsio.gnomish.executorcontract.SettingsSchema;
import com.github.oinsio.gnomish.executorcontract.SettingsViolation;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Whether the settings an executor receives satisfy the schema its describe declared (design D5 of
 * define-executor-contract, phase b): for each stage that names the executor, the settings that
 * stage sends — the one merge {@link ExecutorSettings} owns, of the declaration's settings, the
 * stage's own {@code executor.settings} and its {@code model} — judged by the contract's one judge,
 * {@link SettingsSchema}; the very values the round's request carries, so the judged map is the
 * sent map (task 7.1). A declaration no stage names is judged on its own settings. Each violation
 * is a located {@link ConfigError} where the operator wrote the value — {@code
 * executors.<name>.settings[.<path>]} in {@code config.yaml}, or the stage manifest's {@code
 * executor.settings[.<path>]} or {@code executor.model} — naming the executor and the schema rule:
 * the law error the task parks with, before the first round. One error stated by several stages
 * (a declaration's value every one of them sends) is reported once. A schema the factory cannot
 * use is the program's configuration error, reported as every other describe the program answered
 * wrongly. Pure.
 *
 * <p>Implements FR9, UX1 of define-executor-contract.
 */
final class SettingsAgreement {

    private static final String CONFIG = "config.yaml";

    private SettingsAgreement() {}

    /**
     * The located errors of the settings {@code definition} sends {@code declaration}'s executor.
     *
     * @param definition the law; never null
     * @param declaration the law's declaration of the executor; never null
     * @param describe the executor's describe answer; never null
     * @return every located error, stages in pipeline order, each in the validator's order;
     *     immutable, possibly empty
     */
    static List<ConfigError> errors(PipelineDefinition definition, ExecutorDeclaration declaration, Describe describe) {
        String name = declaration.name();
        List<StageDefinition> stages = definition.stages().stream()
                .filter(stage -> stage.executor().type() == ExecutorType.PROGRAM
                        && name.equals(stage.executor().name()))
                .toList();
        Set<ConfigError> errors = new LinkedHashSet<>();
        if (stages.isEmpty()) {
            errors.addAll(judged(name, null, ExecutorSettings.of(declaration), describe));
        }
        for (StageDefinition stage : stages) {
            errors.addAll(judged(name, stage, ExecutorSettings.of(declaration, stage.executor()), describe));
        }
        return List.copyOf(errors);
    }

    private static List<ConfigError> judged(
            String name, @Nullable StageDefinition stage, ExecutorSettings settings, Describe describe) {
        return switch (SettingsSchema.judge(describe.settingsSchema(), settings.values())) {
            case SettingsJudgement.Accepted ignored -> List.of();
            case SettingsJudgement.Rejected rejected -> {
                List<ConfigError> located = new ArrayList<>();
                for (SettingsViolation violation : rejected.violations()) {
                    located.add(located(name, stage, settings, violation));
                }
                yield located;
            }
            case SettingsJudgement.Unusable unusable ->
                List.of(ExecutorDescribeRefusedException.configuration(
                                name,
                                "its describe declares a settings schema the factory cannot use",
                                unusable.reason())
                        .error());
        };
    }

    private static ConfigError located(
            String name, @Nullable StageDefinition stage, ExecutorSettings settings, SettingsViolation violation) {
        String path = violation.path();
        String message = "executor '%s' settings break its schema's rule '%s': %s"
                .formatted(
                        name,
                        violation.rule(),
                        violation.explanation().excerpt(ExecutorDescribeRefusedException.DETAIL_CAP_CHARS));
        ExecutorSettings.Origin origin = settings.originOf(path);
        if (stage == null || origin == ExecutorSettings.Origin.DECLARATION) {
            String where = "executors." + name + ".settings";
            return new ConfigError(CONFIG, path.isEmpty() ? where : where + "." + path, message);
        }
        String manifest = "stages/%s/stage.yaml".formatted(stage.name());
        if (origin == ExecutorSettings.Origin.MODEL) {
            return new ConfigError(manifest, "executor.model", message);
        }
        return new ConfigError(manifest, "executor.settings." + path, message);
    }
}
