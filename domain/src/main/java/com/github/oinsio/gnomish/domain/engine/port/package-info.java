/**
 * Engine port interfaces, discovered from the consumer side.
 *
 * <p>Defines the ports the stage engine drives instead of touching the outside
 * world: {@code StageExecutor} (a stage round and every check's rounds, design
 * D18 of migrate-verify-to-executor-contract and D2 of extract-agent-round-runner),
 * {@code EngineEventListener}, {@code AttemptPersistence}, {@code AttemptDelivery}
 * and {@code RoundIdentity}. Pure contracts
 * only — no filesystem, no Jackson, no adapter dependencies; the boundary over
 * {@code ..domain..} is enforced by ArchUnit (design D9 of add-stage-engine).
 *
 * <p>Null-marked (JSpecify): every type usage in this package is non-null by
 * default; nullable ones must carry an explicit {@code @Nullable}.
 */
@NullMarked
package com.github.oinsio.gnomish.domain.engine.port;

import org.jspecify.annotations.NullMarked;
