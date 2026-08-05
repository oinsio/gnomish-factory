package com.github.oinsio.gnomish.app;

import java.nio.file.Path;

/**
 * The parsed flags of one {@code gnomish board} invocation, produced by {@link
 * BoardArgumentsParser} (task 3.2).
 *
 * <p>Implements FR1 of add-board-command.
 *
 * @param dir the target project directory (the {@code --dir} value); defaults to {@code .}
 * @param json whether {@code --json} was given
 * @param limit the {@code listReady} window size (the {@code --limit} value); defaults to 50,
 *     always positive
 */
record BoardArguments(Path dir, boolean json, int limit) {}
