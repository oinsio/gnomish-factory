package com.github.oinsio.gnomish.app;

import java.nio.file.Path;

/**
 * The parsed flags of one {@code gnomish usage} invocation, produced by {@link
 * UsageArgumentsParser} (task 5.6). Unlike {@link StatusArguments}, {@code task} is required —
 * {@code gnomish usage} has no list mode.
 *
 * <p>Implements FR14 of add-git-workflow.
 *
 * @param dir the target project clone directory (the {@code --dir} value); unresolved, not
 *     checked for existence here
 * @param task the task id to reconstruct usage for
 * @param json whether {@code --json} was given; selects JSON rendering over text
 */
record UsageArguments(Path dir, String task, boolean json) {}
