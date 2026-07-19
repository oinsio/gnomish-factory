package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The parsed flags of one {@code gnomish status} invocation, produced by
 * {@link StatusArgumentsParser} (task 5.3). {@code task} is {@code null} for the list-mode
 * invocation ({@code gnomish status --dir <clone>}, task 5.4).
 *
 * <p>Implements FR13, FR6 of add-git-workflow.
 *
 * @param dir the target project clone directory (the {@code --dir} value); unresolved, not
 *     checked for existence here
 * @param task the task id to inspect, or {@code null} for list mode
 * @param json whether {@code --json} was given; selects JSON rendering over text
 */
record StatusArguments(Path dir, @Nullable String task, boolean json) {}
