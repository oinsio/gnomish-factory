package com.github.oinsio.gnomish.domain.pipeline;

/**
 * The one spelling of the located errors over a stage's Mechanism {@code model} and
 * {@code command} pair (design D3 of add-command-executor). Two layers raise them:
 * {@link StageMechanismRule} on the typed model, and the law load's wire check for the one case
 * the mapping erases — a field declared blank, which reaches the typed model as absent (a blank
 * {@code model} maps to {@code ""}, a blank {@code command} builds no {@link CommandLine}). Both
 * call these factories, so an author reads one sentence for one fault whichever layer saw it.
 *
 * <p>Implements FR2, UX2 of add-command-executor.
 */
public final class MechanismFieldErrors {

    static final String MODEL = "executor.model";
    static final String COMMAND = "executor.command";

    private MechanismFieldErrors() {}

    /**
     * A {@code model} declared on a {@code command} stage.
     *
     * @param manifest the stage manifest path, relative to the {@code .gnomish/} root
     * @return the located error at {@code executor.model}
     */
    public static ConfigError modelOnCommandStage(String manifest) {
        return new ConfigError(
                manifest,
                MODEL,
                "executor model is not allowed on a command stage; a command stage runs its declared command, not a model");
    }

    /**
     * A {@code command} declared on a stage of another executor type.
     *
     * @param manifest the stage manifest path, relative to the {@code .gnomish/} root
     * @param typeToken the stage's executor wire token ({@code api}, {@code agent-cli}, {@code program})
     * @return the located error at {@code executor.command}
     */
    public static ConfigError commandOnStage(String manifest, String typeToken) {
        return new ConfigError(
                manifest,
                COMMAND,
                "executor command is not allowed on a %s stage; only a command stage declares a command"
                        .formatted(typeToken));
    }

    /**
     * A {@code command} stage with no command (absent, or declared blank).
     *
     * @param manifest the stage manifest path, relative to the {@code .gnomish/} root
     * @return the located error at {@code executor.command}
     */
    public static ConfigError missingCommand(String manifest) {
        return new ConfigError(
                manifest,
                COMMAND,
                "missing required executor command; a command stage must declare the command it runs");
    }
}
