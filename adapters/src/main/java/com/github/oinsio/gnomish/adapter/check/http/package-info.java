/**
 * The built-in {@code http} external-check provider (FR9, FR10, FR11, design D4 of
 * add-plugin-architecture): the escape hatch that points a stage at a third-party CI or quality
 * service's REST endpoint without writing an adapter, symmetric to the {@code command} check for
 * local verification.
 *
 * <p>It ships in core rather than in a vendor bundle, but it is <em>not</em> privileged: it is
 * discovered through the same {@code META-INF/services} pass as {@code github} or any third-party
 * jar, and the engine reaches it through the same {@code ExternalCheckClient} port.
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
