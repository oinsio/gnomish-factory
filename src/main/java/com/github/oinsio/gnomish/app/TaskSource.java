package com.github.oinsio.gnomish.app;

import java.nio.file.Path;

/**
 * The origin of the ad-hoc task description, per {@code gnomish run}'s mutually exclusive
 * {@code --task} / {@code --task-file} flags: either the description text inline on the command
 * line, or a path to a file the runner reads it from (design D5). Exactly one variant is ever
 * constructed by {@link RunArgumentsParser} — the parser itself enforces the "exactly one of"
 * rule (FR1); this type only distinguishes which one.
 *
 * <p>Implements FR1 of add-manual-run.
 */
public sealed interface TaskSource {

    /**
     * The task description supplied verbatim via {@code --task=<text>}.
     *
     * @param text the raw description text; never blank (parser-enforced)
     */
    record Inline(String text) implements TaskSource {}

    /**
     * A path to a file holding the task description, supplied via {@code --task-file=<path>}.
     * The parser does not read or validate the file (task 7.2/7.3's job) — only the path itself
     * is parsed here.
     *
     * @param path the file path, unresolved and unchecked for existence
     */
    record FromFile(Path path) implements TaskSource {}
}
