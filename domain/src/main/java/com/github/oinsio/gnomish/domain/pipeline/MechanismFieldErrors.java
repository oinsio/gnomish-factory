package com.github.oinsio.gnomish.domain.pipeline;

/**
 * The one spelling of the located errors over a stage's Mechanism {@code model} and
 * {@code command} pair (design D3 of add-command-executor). Two layers raise them:
 * {@link StageMechanismRule} on the typed model, and the law load's wire check for the one case
 * the mapping erases — a field declared blank, which reaches the typed model as absent (a blank
 * {@code model} maps to {@code ""}, a blank {@code command} builds no {@link CommandLine}). Both
 * call these factories, so an author reads one sentence for one fault whichever layer saw it.
 *
 * <p>Each factory takes the location of the executor block the fault is in — {@link #STAGE_BLOCK}
 * for a stage's own block, {@code verify[<i>].executor} for a {@code judge} check's (design D12 of
 * extract-agent-round-runner: one block, one rule, wherever it is declared).
 *
 * <p>Implements FR2, UX2 of add-command-executor; FR13, UX1 of extract-agent-round-runner.
 */
public final class MechanismFieldErrors {

    /** The location of a stage's own executor block. */
    public static final String STAGE_BLOCK = "executor";

    private MechanismFieldErrors() {}

    /**
     * A {@code model} declared on a {@code command} stage.
     *
     * @param manifest the stage manifest path, relative to the {@code .gnomish/} root
     * @param where the block's location ({@link #STAGE_BLOCK} or {@code verify[<i>].executor})
     * @return the located error at {@code <where>.model}
     */
    public static ConfigError modelOnCommandStage(String manifest, String where) {
        return new ConfigError(
                manifest,
                where + ".model",
                "executor model is not allowed on a command stage; a command stage runs its declared command, not a model");
    }

    /**
     * A {@code command} declared on a stage of another executor type.
     *
     * @param manifest the stage manifest path, relative to the {@code .gnomish/} root
     * @param where the block's location ({@link #STAGE_BLOCK} or {@code verify[<i>].executor})
     * @param typeToken the block's executor wire token ({@code api}, {@code agent-cli}, {@code program})
     * @return the located error at {@code <where>.command}
     */
    public static ConfigError commandOnStage(String manifest, String where, String typeToken) {
        return new ConfigError(
                manifest,
                where + ".command",
                "executor command is not allowed on a %s stage; only a command stage declares a command"
                        .formatted(typeToken));
    }

    /**
     * A {@code command} stage with no command (absent, or declared blank).
     *
     * @param manifest the stage manifest path, relative to the {@code .gnomish/} root
     * @param where the block's location ({@link #STAGE_BLOCK} or {@code verify[<i>].executor})
     * @return the located error at {@code <where>.command}
     */
    public static ConfigError missingCommand(String manifest, String where) {
        return new ConfigError(
                manifest,
                where + ".command",
                "missing required executor command; a command stage must declare the command it runs");
    }
}
