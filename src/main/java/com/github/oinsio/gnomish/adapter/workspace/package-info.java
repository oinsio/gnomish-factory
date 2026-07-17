/**
 * Concrete {@link com.github.oinsio.gnomish.domain.engine.port.Workspace}
 * implementation for manual runs: wraps the operator-supplied {@code --project}
 * directory and exposes its root path to non-domain code. The domain never
 * inspects a workspace (design D1); check runners ({@code adapter.check}) and
 * console adapters ({@code adapter.console}) are exactly the code that needs
 * the filesystem root, so this package is their shared dependency rather than
 * either owning it.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.workspace;

import org.jspecify.annotations.NullMarked;
