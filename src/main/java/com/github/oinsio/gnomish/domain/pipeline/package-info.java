/**
 * Pure pipeline configuration model and semantic validators.
 *
 * <p>Holds the typed, immutable model of a {@code .gnomish/} pipeline and the pure
 * (catalog-free) validation rules over it. No filesystem, no Jackson, no network —
 * the boundary is enforced by ArchUnit (design D1, FR10 of load-pipeline-config).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.domain.pipeline;

import org.jspecify.annotations.NullMarked;
