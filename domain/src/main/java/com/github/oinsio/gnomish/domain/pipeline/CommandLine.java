package com.github.oinsio.gnomish.domain.pipeline;

/**
 * The command a {@code command} stage runs — the manifest's {@code executor.command}, the
 * mechanism identity of a deterministic stage as the pinned {@code model} is of an agent stage
 * (design D3 of add-command-executor). Its own value type, so the Mechanism never carries it as a
 * third {@code String} beside {@code model} and {@code name}, a transposition the compiler could
 * not see ({@code process-invariants.md}, "Parameter count limit").
 *
 * <p>The command is carried exactly as declared — no trimming, no parsing; the shell of the task
 * execution environment interprets it. A blank command names no mechanism and is refused here;
 * the manifest's blank or absent {@code executor.command} is reported as a located error by the
 * law load before a {@code CommandLine} is built.
 *
 * <p>Implements FR1, FR2 of add-command-executor.
 *
 * @param value the command string; never blank
 */
public record CommandLine(String value) {

    public CommandLine {
        value = requireNonBlank(value);
    }

    /**
     * Fails fast on a blank command. An explicit static method rather than inline in the compact
     * constructor: PIT's record filter suppresses every mutation inside a record's canonical
     * constructor, which would silently exempt this check from the mutation gate.
     */
    private static String requireNonBlank(String value) {
        if (value.isBlank()) {
            throw new IllegalArgumentException("CommandLine.value must not be blank");
        }
        return value;
    }
}
