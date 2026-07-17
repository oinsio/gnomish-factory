/**
 * Production cross-cutting adapters for the engine's environment and persistence
 * ports: {@link com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence},
 * {@link com.github.oinsio.gnomish.adapter.engine.SystemClock}, and
 * {@link com.github.oinsio.gnomish.adapter.engine.ThreadSleeper}. These don't fit
 * {@code adapter.console}/{@code adapter.check}/{@code adapter.workspace} — they are
 * not interactive, not check-specific, and not workspace I/O, but the plain
 * process-lifetime environment the engine runs against (design D8, D10).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.engine;

import org.jspecify.annotations.NullMarked;
