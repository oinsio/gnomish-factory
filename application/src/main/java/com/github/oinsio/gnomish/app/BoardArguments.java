package com.github.oinsio.gnomish.app;

import java.nio.file.Path;

/**
 * The parsed flags of one {@code gnomish board} invocation, produced by {@link
 * BoardArgumentsParser} (task 3.2).
 *
 * <p>Implements FR1 of add-board-command.
 *
 * @param dir the target project directory (the {@code --dir} value, or the working directory),
 *     absolute and normalized (FR7 of fix-operator-blockers)
 * @param json whether {@code --json} was given
 * @param limit the {@code listReady} window size (the {@code --limit} value); defaults to 50,
 *     always positive
 */
record BoardArguments(Path dir, boolean json, int limit) {

    BoardArguments {
        ArgumentsParsingSupport.requireAbsoluteDir(dir);
    }
}
