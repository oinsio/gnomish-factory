package com.github.oinsio.gnomish.app;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * The parsed flags of one {@code gnomish dashboard} invocation, produced by {@link
 * DashboardArgumentsParser} (task 4.1).
 *
 * <p>Implements FR1, FR7 of add-dashboard-page.
 *
 * @param dir the target project directory (the {@code --dir} value); defaults to {@code .}
 * @param out the explicit output path (the {@code --out} value), or {@code null} to use the
 *     default instance-directory path (design D8)
 * @param watch whether {@code --watch} was given (FR7)
 */
record DashboardArguments(Path dir, @Nullable Path out, boolean watch) {}
