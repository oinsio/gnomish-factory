/**
 * The two concrete {@link com.github.oinsio.gnomish.domain.engine.port.Workspace} realizations
 * the run hands to check runners and console adapters: {@link
 * com.github.oinsio.gnomish.app.workspace.DirectoryWorkspace} (host mode — the operator-supplied
 * {@code --dir} directory, exposing its root path) and {@link
 * com.github.oinsio.gnomish.app.workspace.RecordedAttemptCommitWorkspace} (sandboxed mode — the
 * {@code AttemptCommitRef} naming the round's harvested attempt commit). The domain never
 * inspects a workspace (design D1); consumers downcast to the variant their mode implies.
 *
 * <p>Application layer, not adapters (task 4.4, D12(a) of split-into-modules): both are value
 * wrappers over a path and a ref respectively, with no external system behind them — the same
 * treatment task 4.2 gave {@code SystemClock} / {@code ThreadSleeper}. They live here rather than
 * in {@code :domain} because {@code DirectoryWorkspace} validates its root through {@code
 * java.nio.file}, which the domain-purity gate forbids.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.workspace;

import org.jspecify.annotations.NullMarked;
