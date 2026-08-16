/**
 * The snapshot writer thread and its atomic file-write mechanics (design D4 of
 * add-serve-observability, task group 3): the single writer thread that beats on
 * a timer and wakes early on a dirty-flag trigger, and the temp-file-plus-rename
 * primitive that makes each write atomic — a reader of the target file never
 * observes a partial write (FR1).
 *
 * <p>This package does not yet wire the real transition triggers (task 3.2), the
 * {@code writtenAt}/{@code intervalSeconds} self-description (task 3.3, already
 * carried by {@link com.github.oinsio.gnomish.serveobservability.Snapshot} itself),
 * lifecycle states (task 3.4), or the ledger retention sweep (task 3.5); those are
 * later task groups layered on top of this thread.
 *
 * <p>Implements FR1 of add-serve-observability.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.serveobservability.writer;

import org.jspecify.annotations.NullMarked;
