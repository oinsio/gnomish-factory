/**
 * Production cross-cutting adapters for the engine's persistence port:
 * {@link com.github.oinsio.gnomish.adapter.engine.InMemoryAttemptPersistence}. The environment
 * ports — the time equipment, a system {@link java.time.InstantSource} and the real sleeper — are
 * built once by the composition root in {@code :bootstrap} (design D20 of
 * supervise-daemon-loops-and-embed-dashboard), not here. These don't fit
 * {@code adapter.check}/{@code adapter.workspace} — they are not check-specific and
 * not workspace I/O, but the plain
 * process-lifetime environment the engine runs against (design D8, D10).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.engine;

import org.jspecify.annotations.NullMarked;
