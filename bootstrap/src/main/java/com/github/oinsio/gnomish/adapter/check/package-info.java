/**
 * Bootstrap-owned check-provider wiring: {@link
 * com.github.oinsio.gnomish.adapter.check.ProviderDispatchingExternalCheckClient} routes each
 * {@code external} check to the client of the provider it selects, and {@link
 * com.github.oinsio.gnomish.adapter.check.CheckClientDiscovery} builds that provider registry from
 * one {@link java.util.ServiceLoader} pass (FR2, FR3, FR5, FR6, FR15 of add-plugin-architecture).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.check;

import org.jspecify.annotations.NullMarked;
