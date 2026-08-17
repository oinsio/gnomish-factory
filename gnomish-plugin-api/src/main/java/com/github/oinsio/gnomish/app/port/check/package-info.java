/**
 * The check-side half of the published plugin surface: the pin-path contribution hook a check
 * provider exposes through {@link com.github.oinsio.gnomish.app.CheckClientFactory}.
 *
 * <p>The polled port itself ({@code ExternalCheckClient}) and its value model ({@code PollStatus},
 * {@code Finding}, {@code Workspace}) deliberately stay in {@code :domain}: the engine consumes
 * them from inside the domain, so relocating them here would close the layering cycle the build
 * forbids. They reach a third party through this module's transitive {@code api} edge on {@code
 * :domain}, so the "single declared dependency" contract is unaffected.
 *
 * <p>Implements FR16 of add-sandbox-core; FR15 of add-plugin-architecture.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.app.port.check;

import org.jspecify.annotations.NullMarked;
