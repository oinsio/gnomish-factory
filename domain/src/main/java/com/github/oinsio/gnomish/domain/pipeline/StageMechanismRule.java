package com.github.oinsio.gnomish.domain.pipeline;

import java.util.List;

/**
 * The stage-level rule over a stage's Mechanism section — what pins the executor's identity in the
 * manifest, per executor type (design D3 of add-command-executor):
 *
 * <ul>
 *   <li>an agent stage ({@code api}, {@code agent-cli}) pins a non-blank {@code model}, so any
 *       instance reproduces the stage identically, never left to a CLI default (FR11 of
 *       load-pipeline-config);</li>
 *   <li>a {@code program} stage may omit the model; a present one is carried to the executor as a
 *       setting (FR10 of define-executor-contract);</li>
 *   <li>a {@code command} stage declares its {@code executor.command} and no {@code model}: the
 *       manifest must not claim a model where a command runs (FR2 of add-command-executor);</li>
 *   <li>no {@code api}, {@code agent-cli} or {@code program} stage declares a command (FR2).</li>
 * </ul>
 *
 * <p>A {@link CommandLine} is never blank, so a blank manifest command is the law load's located
 * error before the domain model exists, never an absent command here; likewise an absent model
 * reaches this rule as blank. Errors are located {@link ConfigError}s on the stage manifest
 * (UX2), spelled by {@link MechanismFieldErrors}, appended model first, then command; nothing is
 * thrown. Called by
 * {@link StageSanityRule}, which owns the per-stage error order.
 *
 * <p>Implements FR11 of load-pipeline-config; FR10 of define-executor-contract; FR2, UX2 of
 * add-command-executor.
 */
final class StageMechanismRule {

    private StageMechanismRule() {}

    static void validate(String manifest, StageDefinition.Executor executor, List<ConfigError> errors) {
        switch (executor.type()) {
            case API -> {
                requireModel(manifest, executor, errors);
                rejectCommand(manifest, executor, "api", errors);
            }
            case AGENT_CLI -> {
                requireModel(manifest, executor, errors);
                rejectCommand(manifest, executor, "agent-cli", errors);
            }
            // A program stage may omit the model; a present one is a setting (FR10 of define-executor-contract).
            case PROGRAM -> rejectCommand(manifest, executor, "program", errors);
            case COMMAND -> {
                rejectModel(manifest, executor, errors);
                requireCommand(manifest, executor, errors);
            }
        }
    }

    private static void requireModel(String manifest, StageDefinition.Executor executor, List<ConfigError> errors) {
        if (executor.model().isBlank()) {
            errors.add(
                    new ConfigError(
                            manifest,
                            "executor.model",
                            "missing required executor model; the model must be pinned in the manifest for every agent executor type (api, agent-cli)"));
        }
    }

    private static void rejectModel(String manifest, StageDefinition.Executor executor, List<ConfigError> errors) {
        if (!executor.model().isBlank()) {
            errors.add(MechanismFieldErrors.modelOnCommandStage(manifest));
        }
    }

    private static void requireCommand(String manifest, StageDefinition.Executor executor, List<ConfigError> errors) {
        if (executor.command() == null) {
            errors.add(MechanismFieldErrors.missingCommand(manifest));
        }
    }

    private static void rejectCommand(
            String manifest, StageDefinition.Executor executor, String type, List<ConfigError> errors) {
        if (executor.command() != null) {
            errors.add(MechanismFieldErrors.commandOnStage(manifest, type));
        }
    }
}
