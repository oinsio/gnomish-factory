/**
 * The git adapter: task branch, worktree, and state-file mechanics realized as {@code git}
 * subprocess calls ({@link com.github.oinsio.gnomish.adapter.git.GitProcessRunner}) plus the
 * branch-naming, push, and worktree-lifecycle policies layered on top (design D3, D6, D10).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.git;

import org.jspecify.annotations.NullMarked;
