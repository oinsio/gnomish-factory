/**
 * The daemon's observed sweep bracket (add-serve-sandbox-lifecycle, NFR-O1, NFR-O2): wraps the
 * per-project {@code SandboxLifecyclePass} so each run is one tick, with verdicts fanned out to
 * the snapshot's tick log and the ledger's action sink.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.sandboxlifecycle;

import org.jspecify.annotations.NullMarked;
