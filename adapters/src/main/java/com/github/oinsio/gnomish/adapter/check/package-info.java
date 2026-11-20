/**
 * Real built-in and command check-runner adapters for manual runs (FR6, FR7 of
 * add-manual-run): {@code files_exist} and the {@code sh -c} command runner,
 * both operating against a {@link com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace}
 * on disk. The domain never touches the filesystem (design D1); this package
 * is where the {@code files-exist} and {@code shell} built-in verdict executors
 * meet the real world (the former check-runner ports left with task 5.2 of
 * migrate-verify-to-executor-contract).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.check;

import org.jspecify.annotations.NullMarked;
