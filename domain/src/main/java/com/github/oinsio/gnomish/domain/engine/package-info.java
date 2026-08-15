/**
 * Stage-engine domain model and pure orchestrator.
 *
 * <p>Holds the immutable stage-engine records and the orchestration engine that
 * drives a task through its pipeline stages. A pure orchestrator: no tracker,
 * filesystem, or git — everything real happens behind the ports in
 * {@code domain.engine.port}. SLF4J logging is permitted; filesystem, Jackson,
 * and adapter dependencies are forbidden — the boundary over {@code ..domain..}
 * is enforced by ArchUnit (design D9 of add-stage-engine).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.domain.engine;

import org.jspecify.annotations.NullMarked;
