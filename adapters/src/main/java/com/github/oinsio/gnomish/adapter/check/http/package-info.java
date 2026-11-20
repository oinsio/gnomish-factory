/**
 * The built-in {@code http} verdict executor (FR9, FR10, FR11, design D4 of
 * add-plugin-architecture; FR6 of migrate-verify-to-executor-contract): the escape hatch that points
 * a stage at a third-party CI or quality service's REST endpoint without writing an adapter,
 * symmetric to the {@code command} check for local verification.
 *
 * <p>It is compiled into and named by core and registered in the executor registry like every
 * built-in; the engine reaches it through its {@code StageExecutor} port as a check round. No
 * {@code ServiceLoader} pass discovers it (task 5.2 of migrate-verify-to-executor-contract).
 *
 * <p>The verdict is declared, not coded — a {@code pass-when} narrowing HTTP 2xx by an optional
 * jsonPath and/or regex extraction, and an optional {@code pending-when} that keeps the check
 * polling — so a new service needs a manifest entry, not Java.
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by default; nullable ones
 * must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.check.http;

import org.jspecify.annotations.NullMarked;
