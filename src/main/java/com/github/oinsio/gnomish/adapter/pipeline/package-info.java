/**
 * I/O side of pipeline configuration loading.
 *
 * <p>Reads {@code .gnomish/} files, parses YAML via Jackson DTOs, maps them into the
 * pure domain model, and runs the I/O-bound checks (file existence, path-traversal
 * rejection). Everything touching the outside world lives here, keeping
 * {@code domain.pipeline} pure (design D1, FR10 of load-pipeline-config).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.adapter.pipeline;

import org.jspecify.annotations.NullMarked;
