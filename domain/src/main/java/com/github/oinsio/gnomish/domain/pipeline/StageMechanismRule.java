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
 * <p>It is the one rule over an executor block wherever the block is declared (design D12 of
 * extract-agent-round-runner): a stage's own {@code executor:}, located at {@code executor.*}, and a
 * {@code judge} check's, located at {@code verify[<i>].executor.*} — so a judge block without a
 * model gets the very error a stage's block without one gets, at its own location.
 *
 * <p>A {@link CommandLine} is never blank, so a blank manifest command is the law load's located
 * error before the domain model exists, never an absent command here; likewise an absent model
 * reaches this rule as blank. Errors are located {@link ConfigError}s on the stage manifest
 * (UX2), spelled by {@link MechanismFieldErrors}, appended model first, then command; nothing is
 * thrown. Called by
 * {@link StageSanityRule}, which owns the per-stage error order.
 *
 * <p>Implements FR11 of load-pipeline-config; FR10 of define-executor-contract; FR2, UX2 of
 * add-command-executor; FR13, UX1 of extract-agent-round-runner.
 */
final class StageMechanismRule {

    private StageMechanismRule() {}

    /**
     * Judges one executor block.
     *
     * @param manifest the stage manifest path
     * @param where the block's location: {@link MechanismFieldErrors#STAGE_BLOCK} or {@code
     *     verify[<i>].executor}
     * @param executor the block
     * @param errors the caller's accumulator
     */
    static void validate(String manifest, String where, StageDefinition.Executor executor, List<ConfigError> errors) {
        switch (executor.type()) {
            case API -> {
                requireModel(manifest, where, executor, errors);
                rejectCommand(manifest, where, executor, "api", errors);
            }
            case AGENT_CLI -> {
                requireModel(manifest, where, executor, errors);
                rejectCommand(manifest, where, executor, "agent-cli", errors);
            }
            // A program stage may omit the model; a present one is a setting (FR10 of define-executor-contract).
            case PROGRAM -> rejectCommand(manifest, where, executor, "program", errors);
            case COMMAND -> {
                rejectModel(manifest, where, executor, errors);
                requireCommand(manifest, where, executor, errors);
            }
        }
    }

    private static void requireModel(
            String manifest, String where, StageDefinition.Executor executor, List<ConfigError> errors) {
        if (executor.model().isBlank()) {
            errors.add(
                    new ConfigError(
                            manifest,
                            where + ".model",
                            "missing required executor model; the model must be pinned in the manifest for every agent executor type (api, agent-cli)"));
        }
    }

    private static void rejectModel(
            String manifest, String where, StageDefinition.Executor executor, List<ConfigError> errors) {
        if (!executor.model().isBlank()) {
            errors.add(MechanismFieldErrors.modelOnCommandStage(manifest, where));
        }
    }

    private static void requireCommand(
            String manifest, String where, StageDefinition.Executor executor, List<ConfigError> errors) {
        if (executor.command() == null) {
            errors.add(MechanismFieldErrors.missingCommand(manifest, where));
        }
    }

    private static void rejectCommand(
            String manifest, String where, StageDefinition.Executor executor, String type, List<ConfigError> errors) {
        if (executor.command() != null) {
            errors.add(MechanismFieldErrors.commandOnStage(manifest, where, type));
        }
    }
}
