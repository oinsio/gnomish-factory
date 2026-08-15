/**
 * Engine port interfaces, discovered from the consumer side.
 *
 * <p>Defines the seven ports the stage engine drives instead of touching the
 * outside world: {@code StageExecutor}, {@code BuiltinCheckRunner},
 * {@code CommandCheckRunner}, {@code ExternalCheckClient}, {@code JudgeVoter},
 * {@code EngineEventListener}, and {@code AttemptPersistence}. Pure contracts
 * only — no filesystem, no Jackson, no adapter dependencies; the boundary over
 * {@code ..domain..} is enforced by ArchUnit (design D9 of add-stage-engine).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.domain.engine.port;

import org.jspecify.annotations.NullMarked;
