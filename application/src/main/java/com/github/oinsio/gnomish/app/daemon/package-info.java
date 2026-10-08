/**
 * Supervision of the factory's daemon loops (supervise-daemon-loops-and-embed-dashboard, design
 * D1–D5): {@link com.github.oinsio.gnomish.app.daemon.SupervisedLoop}, the one shape of a
 * long-lived daemon thread, with its wait, its restart policy and the restart backoff a supervised
 * loop waits before respawning a dead worker.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable
 * ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.daemon;

import org.jspecify.annotations.NullMarked;
